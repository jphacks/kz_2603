package jp.climafit.app

import android.content.ActivityNotFoundException
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

private enum class Tab(val label: String, val icon: Int) {
    OUTFITS("気温別コーデ", R.drawable.ic_nav_outfits),
    HOME("ホーム", R.drawable.ic_home),
    COMMUNITY("今日のひろば", R.drawable.ic_nav_people)
}
private enum class RoomPicker { HOME, COMMUNITY }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // local.properties に db_server の設定があればバックエンドにつなぐ（無ければサンプル表示）
        val backend = (application as? LayeRoomApplication)?.backend
        setContent { LayeRoomTheme { LayeRoomApp(viewModel(factory = LayeRoomViewModel.Factory(application, backend))) } }
    }
}
@Composable
internal fun LayeRoomApp(model: LayeRoomViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var aiMode by rememberSaveable { mutableStateOf<AiMode?>(null) }
    var roomPicker by rememberSaveable { mutableStateOf<RoomPicker?>(null) }
    var communityLocation by rememberSaveable { mutableStateOf<String?>(null) }
    var showRegistration by remember { mutableStateOf(false) }
    var showCamera by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val screens = rememberSaveableStateHolder()
    val notice = model.notice
    LaunchedEffect(notice) { notice?.let { snackbar.showSnackbar(it); model.dismissNotice() } }

    fun backHome() {
        if (aiMode == AiMode.PHOTO) model.clearPhoto()
        aiMode = null
        tab = Tab.HOME
    }
    fun acceptPhoto(uri: Uri) {
        showCamera = false
        model.loadPhoto(uri)
        aiMode = AiMode.PHOTO
    }
    fun acceptCaptured(jpeg: ByteArray) {
        showCamera = false
        model.loadCapturedPhoto(jpeg)
        aiMode = AiMode.PHOTO
    }
    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) acceptPhoto(uri)
    }
    fun openPhotoPicker() {
        try { photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
        catch (_: ActivityNotFoundException) { model.message("写真選択を開けませんでした。端末の写真アプリをご確認ください。") }
    }
    BackHandler(enabled = aiMode != null) { backHome() }
    BackHandler(enabled = showCamera) { showCamera = false }
    // 写真でコーデ診断は、まずアプリのカメラを開く（左下のボタンから写真フォルダも選べる）
    if (showCamera) CameraScreen(onCaptured = ::acceptCaptured, onPickFromGallery = ::openPhotoPicker,
        onClose = { showCamera = false })
    else Scaffold(
        containerColor = Paper,
        topBar = {
            // Neo 風：左に黒いチップ（ロゴ）、右にグレーの角丸ボタン。その下に大きな太字の見出し（今のタブ名）
            Column(Modifier.fillMaxWidth().background(Paper).statusBarsPadding()
                .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = CircleShape, color = Ink, shadowElevation = 4.dp) {
                        Row(Modifier.padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Image(painterResource(R.drawable.ic_layeroom_mark), contentDescription = null,
                                modifier = Modifier.size(28.dp).clip(CircleShape))
                            Text(androidx.compose.ui.res.stringResource(R.string.app_name), color = Color.White,
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClickLabel = "設定・ユーザー登録") { showRegistration = true }
                        .semantics { contentDescription = "設定・ユーザー登録" }.testTag("open-registration")) {
                        Avatar(model.userName)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(tab.label, style = MaterialTheme.typography.headlineLarge, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("レイルーム · 今日の一着を、心地よく。", style = MaterialTheme.typography.bodySmall, color = Muted,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        bottomBar = {
            // 平らなタブバー（選択中は黒い丸に白いアイコン、ほかは薄いグレー）
            Surface(color = Color(0xFFF7F7F7), border = BorderStroke(0.5.dp, Outline)) {
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Tab.entries.forEach { destination ->
                        val selected = tab == destination
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp))
                            .selectable(selected = selected, role = Role.Tab, onClick = {
                                if (aiMode == AiMode.PHOTO) model.clearPhoto()
                                aiMode = null; tab = destination
                                roomPicker = if (destination == Tab.COMMUNITY) RoomPicker.COMMUNITY else null
                            }).testTag("tab-${destination.name}").padding(vertical = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Box(Modifier.size(32.dp).background(if (selected) Ink else Color.Transparent, CircleShape), contentAlignment = Alignment.Center) {
                                Icon(painterResource(destination.icon), contentDescription = null,
                                    tint = if (selected) Color.White else Faint, modifier = Modifier.size(if (selected) 18.dp else 24.dp))
                            }
                            Text(destination.label, fontSize = 12.sp, maxLines = 1,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, color = if (selected) Ink else Faint)
                        }
                    }
                }
            }
        }, snackbarHost = {
            SnackbarHost(snackbar) { data ->
                Snackbar(data, shape = RoundedCornerShape(14.dp), containerColor = Ink, contentColor = Color.White)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            val selectedAiMode = aiMode
            if (selectedAiMode != null) {
                AiStatusScreen(model, selectedAiMode, onBack = ::backHome, onRetryPhoto = { showCamera = true })
            } else {
                screens.SaveableStateProvider(tab.name) {
                    when (tab) {
                        Tab.HOME -> HomeScreen(model,
                            onPhoto = { showCamera = true },
                            onChooseRoom = { roomPicker = RoomPicker.HOME },
                            onSuggest = { model.requestSuggestionPreview(); aiMode = AiMode.SUGGEST })
                        Tab.OUTFITS -> OutfitsScreen(model)
                        Tab.COMMUNITY -> CommunityScreen(model, communityLocation) { roomPicker = RoomPicker.COMMUNITY }
                    }
                }
            }
        }
    }
    // 未登録のまま起動したら、登録するまで閉じられない登録シートを出す（登録できたら自動で閉じる）
    val mustRegister = model.mustRegister
    if (showRegistration || mustRegister) {
        RegistrationSheet(currentName = model.userName, required = mustRegister, busy = model.registering,
            error = if (mustRegister) model.registrationError else null,
            onDismiss = { showRegistration = false }, onSubmit = { name ->
                model.register(name)
                if (!mustRegister) showRegistration = false
            })
    }
    val pickerTarget = roomPicker
    if (pickerTarget != null) {
        LocationPickerSheet(
            title = if (pickerTarget == RoomPicker.HOME) "おすすめの場所を選ぶ" else "今日のひろばの場所を選ぶ",
            rooms = model.rooms,
            selected = if (pickerTarget == RoomPicker.HOME) model.roomName else communityLocation,
            onDismiss = { roomPicker = null },
            onSelect = { location ->
                if (pickerTarget == RoomPicker.HOME) model.selectRoom(location) else communityLocation = location
                roomPicker = null
            },
        )
    }
}
