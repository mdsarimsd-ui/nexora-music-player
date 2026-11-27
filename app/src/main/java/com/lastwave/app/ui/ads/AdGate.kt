package com.lastwave.app.ui.ads

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * Optional capped ads: a way to support the dev without paying. Off by
 * default; when the user turns it on in Settings, at most [MAX_PER_DAY]
 * interstitials show per day with a minimum gap between them, then the rest
 * of the day stays completely ad-free. The UPI donation prompt is unchanged
 * and keeps asking quietly on its own cadence.
 *
 * The app and ad-unit IDs below are Google's public test placeholders.
 * When the real AdMob publisher account is ready, replace both constants
 * and the APPLICATION_ID meta-data in AndroidManifest.xml.
 */
object AdGate {
    // Google's official test app/unit IDs - safe placeholders that serve
    // test ads only.
    const val TEST_APP_ID = "ca-app-pub-3940256099942544~3347511713"
    const val INTERSTITIAL_UNIT = "ca-app-pub-3940256099942544/1033173712"

    private const val PREFS = "nexora_ads"
    private const val KEY_ENABLED = "ads_enabled"
    private const val KEY_DAY = "ads_day"
    private const val KEY_SHOWN_TODAY = "ads_shown_today"
    private const val KEY_LAST_SHOWN_AT = "ads_last_shown_at"

    private const val MAX_PER_DAY = 3
    private const val MIN_GAP_MS = 90L * 60L * 1000L

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, on).apply()
    }

    /** Warm the SDK once per process; safe to call on every open. */
    fun init() {
        runCatching { MobileAds.initialize {} }
    }

    /**
     * Call on app open, after launch UI settles. Shows at most
     * [MAX_PER_DAY] ads per day, [MIN_GAP_MS] apart; otherwise silent.
     */
    fun maybeShowInterstitial(activity: Activity) {
        if (!enabled(activity)) return
        if (rollDayIfNeeded(activity)) return  // day just rolled, counter at 0
        if (capReached(activity)) return
        if (tooSoon(activity)) return

        InterstitialAd.load(
            activity,
            INTERSTITIAL_UNIT,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    ad.fullScreenContentCallback = object : FullScreenContentCallback() {
                        override fun onAdDismissedFullScreenContent() = Unit
                        override fun onAdFailedToShowFullScreenContent(adError: AdError) = Unit
                    }
                    // Re-check the cap at show time in case the day rolled
                    // over or another show landed while the ad loaded.
                    if (rollDayIfNeeded(activity)) return
                    if (capReached(activity)) return
                    countShown(activity)
                    ad.show(activity)
                }

                override fun onAdFailedToLoad(error: LoadAdError) = Unit
            },
        )
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** True when the stored day is older than today, after resetting the counter. */
    private fun rollDayIfNeeded(context: Context): Boolean {
        val p = prefs(context)
        val today = System.currentTimeMillis() / 86_400_000L
        if (p.getLong(KEY_DAY, -1L) != today) {
            p.edit().putLong(KEY_DAY, today).putInt(KEY_SHOWN_TODAY, 0).apply()
            return true
        }
        return false
    }

    private fun capReached(context: Context): Boolean =
        prefs(context).getInt(KEY_SHOWN_TODAY, 0) >= MAX_PER_DAY

    private fun tooSoon(context: Context): Boolean {
        val lastAt = prefs(context).getLong(KEY_LAST_SHOWN_AT, 0L)
        return lastAt > 0L && System.currentTimeMillis() - lastAt < MIN_GAP_MS
    }

    private fun countShown(context: Context) {
        val p = prefs(context)
        p.edit()
            .putInt(KEY_SHOWN_TODAY, p.getInt(KEY_SHOWN_TODAY, 0) + 1)
            .putLong(KEY_LAST_SHOWN_AT, System.currentTimeMillis())
            .apply()
    }

    /** Used by MainActivity: warm the SDK, then try one show once launch UI settles. */
    fun warmAndMaybeShow(activity: Activity, delayMs: Long = 8_000L) {
        if (!enabled(activity)) return
        init()
        Handler(Looper.getMainLooper()).postDelayed(
            { maybeShowInterstitial(activity) },
            delayMs,
        )
    }
}
