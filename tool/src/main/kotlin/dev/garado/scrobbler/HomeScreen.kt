package dev.garado.scrobbler

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.media.LightMediaSessionAccess
import com.thelightphone.sdk.media.LightNowPlayingTrack
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class HomeScreenViewModel : LightViewModel<Unit>() {
    val nowPlaying: StateFlow<LightNowPlayingTrack?> = LightMediaSessionAccess.nowPlaying
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
}

@InitialScreen
class HomeScreen(sealedActivity: SealedLightActivity) : LightScreen<Unit, HomeScreenViewModel>(sealedActivity) {

    override val viewModelClass: Class<HomeScreenViewModel>
        get() = HomeScreenViewModel::class.java

    override fun createViewModel(): HomeScreenViewModel {
        return HomeScreenViewModel()
    }

    private var accessGranted by mutableStateOf(LightMediaSessionAccess.isGranted(lightContext))

    override fun willShow() {
        super.willShow()
        accessGranted = LightMediaSessionAccess.isGranted(lightContext)
    }

    @Composable
    override fun Content() {
        val nowPlaying by viewModel.nowPlaying.collectAsState()
        val themeColors by LightThemeController.colors.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(center = LightTopBarCenter.Text("Scrobbler"))

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                ) {
                    LightText(
                        text = "Now playing",
                        variant = LightTextVariant.Detail,
                    )
                    LightText(
                        text = nowPlaying?.let { "${it.artist} - ${it.title}" } ?: "No track detected",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier.padding(top = 2.dp, bottom = 16.dp),
                        maxLines = 2,
                    )
                    
                    LightText(
                        text = "Media access",
                        variant = LightTextVariant.Detail,
                    )
                    LightText(
                        text = if (accessGranted) "Enabled" else "Disabled",
                        variant = LightTextVariant.Copy,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .lightClickable(onClick = { LightMediaSessionAccess.requestAccess(lightContext) }),
                    )
                }
            }
        }
    }
}
