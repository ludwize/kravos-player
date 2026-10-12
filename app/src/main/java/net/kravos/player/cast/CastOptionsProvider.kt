package net.kravos.player.cast

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions

/** Uses Google's Default Media Receiver; Media3's session owns the notification and lock-screen controls. */
class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .setStopReceiverApplicationWhenEndingSession(true)
            .setCastMediaOptions(
                CastMediaOptions.Builder()
                    .setMediaSessionEnabled(false)
                    .setNotificationOptions(null)
                    .build(),
            )
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}
