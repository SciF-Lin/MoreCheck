package com.morecheck.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.morecheck.di.appContainer
import com.morecheck.ui.AppViewModelProvider
import kotlinx.coroutines.delay

/**
 * 注册页。
 *
 * 版式照参考图：绿调顶栏 + 一张**顶圆角 28dp 的卡片**铺满其余部分，
 * 卡片内是下划线式表单，底部贴一行协议勾选。
 *
 * 交互（都是纯前端的原型行为，不接后端）：
 * - 「发送验证码」**先看输入框里有没有数字**：没有就只提示「请先输入手机号」且不起倒计时；
 *   有数字才进入 **60 秒倒计时**，并在点击瞬间用一个约 700ms 的小转圈做过渡；
 * - 昵称**只能是中文 / 英文 / 数字**，合法时右侧转约 1 秒小圈后提示「昵称可使用」，
 *   含非法字符则像手机号那样变红报错；
 * - 密码框眼睛可切换明文；
 * - 「注册」在**六项资料全部就绪**前不可点；点击后按钮变灰并转 3~5 秒，
 *   成功后写入登录态与资料、提示「注册成功」并返回上一页；
 * - 《用户协议》《隐私政策》跳到应用里已有的两个真实页面（不是占位）。
 */
@Composable
fun RegisterScreen(
    onBack: () -> Unit,
    onOpenAgreement: () -> Unit,
    onOpenPrivacy: () -> Unit,
    /**
     * 注册成功后往哪走。默认等于 [onBack]（退回上一页）；
     * 目前唯一的调用方 `TabNavHost` 传的是「直接回到『我的信息』」。
     *
     * 需求：「注册后直接返回到个人信息页」。注册这条路的页面栈是
     * `我的信息 → 登录 → 注册账号`，所以要**一次弹掉两层**，
     * 单纯 `popBackStack()` 只会退回登录页。
     *
     * 做成独立回调而不是把路由写死在这里：本页是纯展示页，不该知道路由长什么样。
     */
    onRegistered: () -> Unit = onBack,
    authViewModel: AuthViewModel = viewModel(factory = AppViewModelProvider.Factory)
) {
    val colors = rememberAuthColors()

    var account by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var nickname by rememberSaveable { mutableStateOf("") }
    // 性别用可空字符串保存（`null` = 还没选）：需求要求「所有资料填写完成」才可注册，
    // 所以必须能区分「没选」与「选了某一项」。
    var gender by rememberSaveable { mutableStateOf<String?>(null) }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }

    // 默认勾选：需求要求「首次进入即已同意」。
    // 仍用 rememberSaveable，所以用户手动取消后、返回再进仍保持取消——
    // 这是对的：默认值只应影响首次进入，不该把用户的显式选择反复重置。
    // 「注册」按钮里的 !agreed 分支保留，用户取消勾选时依然会被拦住。
    var agreed by rememberSaveable { mutableStateOf(true) }
    var toast by remember { mutableStateOf<String?>(null) }

    // 开发者选项「验证码测试」的接线（与登录页共用同一实现）。
    // 注册页也有「发送验证码」按钮，因此这里同样要生成 + 通知；
    // 生成的码会由 `AuthViewModel.register` 校验（见其 KDoc），两页行为一致。
    val devCode = rememberDevCodeSender(onMessage = { toast = it })

    // 「发送验证码」的状态机（转圈时长抖动 + 转圈结束后才弹提示）与登录页共用同一实现，
    // 见 `AuthComponents.rememberSendCodeState`——需求要求两页这套交互完全一致。
    val sendState = rememberSendCodeState(
        onSent = {
            // 提示文案跟随「验证码测试」开关：开启时码是真的以通知送达的。
            toast = if (devCode.enabled) {
                "验证码已发送，请查看通知栏"
            } else {
                "验证码已发送（演示，不会真的发出）"
            }
        }
    )

    // 手机号为空时点「发送验证码」的内联错误提示；一输入就清掉。
    var accountError by remember { mutableStateOf(false) }

    // 昵称查重：合法转小圈（时长可在开发者选项调，默认 200ms）→ 提示「昵称可使用」；
    // 含非法字符立刻报红。
    //
    // 2026-10-25 需求：时长不再写死，从开发者选项仓库的**生效值**取
    // （总开关关时仓库回落默认 200ms，与本页其它行为一致）。
    val appContext = LocalContext.current.applicationContext
    val devOptions = remember(appContext) {
        appContext.appContainer.developerOptionsRepository
    }
    val nicknameCheckMs by devOptions.nicknameCheckDurationMs
        .collectAsStateWithLifecycle(initialValue = DEFAULT_NICKNAME_CHECK_MS.toInt())
    val nicknameState = rememberNicknameCheckState(
        nickname,
        durationMs = nicknameCheckMs.toLong()
    )
    val nicknameIllegal = nicknameHasIllegalChar(nickname)

    // 是否正在注册 / 是否刚刚注册成功。
    val registering by authViewModel.isRegistering.collectAsStateWithLifecycle()
    val registerSucceeded by authViewModel.registerSucceeded.collectAsStateWithLifecycle()

    // 注册流程的失败原因（目前只有开发者选项「验证码测试」这一条路径会产生），
    // 与登录页同一套出口：走本页已有的 toast，用完立刻清掉，避免下次回来又弹一次。
    val registerError by authViewModel.error.collectAsStateWithLifecycle()

    // 六项资料全部就绪才允许注册（需求）。
    val canRegister = account.isNotBlank() &&
        code.isNotBlank() &&
        password.isNotBlank() &&
        isNicknameValid(nickname) &&
        gender != null &&
        agreed

    LaunchedEffect(toast) {
        if (toast != null) {
            delay(1600)
            toast = null
        }
    }

    // 注册成功：提示 → 复位信号 → 回到「我的信息」。
    //
    // 提示先于跳转，让「注册成功」这句话留在屏幕上（AuthToast 会自己消失）；
    // 紧接着的 [onRegistered] 会**一次性弹掉注册页与登录页两层**，
    // 落到「我的信息」时登录态已经写入，用户立刻就能看到刚注册的
    // 昵称 / 性别 / 手机号 / 用户 ID。
    LaunchedEffect(registerSucceeded) {
        if (registerSucceeded) {
            toast = "注册成功"
            delay(REGISTER_TOAST_MS)
            authViewModel.consumeRegisterSuccess()
            onRegistered()
        }
    }

    // 失败原因也走 toast（与登录页同一口径），用完立刻清掉。
    LaunchedEffect(registerError) {
        registerError?.let {
            toast = it
            authViewModel.clearError()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.tinted)
    ) {
        Column(Modifier.fillMaxSize()) {
            // ---- 顶栏（底色与页面一致，靠卡片把内容托起来）----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AuthBackButton(
                    onClick = onBack,
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                    tint = colors.accent
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "注册账号",
                    color = colors.onPage,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // ---- 卡片 ----
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(colors.card)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                ) {
                    Spacer(Modifier.height(28.dp))

                    // 2026-10-25 需求：标注本页是**模拟注册**（真实云登录在「登录」页走
                    // 验证码链路），避免用户误以为注册真的建了云账号。
                    Text(
                        text = "模拟注册 · 仅界面演示，不接入云端",
                        color = colors.muted,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.height(16.dp))

                    AuthUnderlineField(
                        value = account,
                        // 只要数字：手机号就是纯数字，顺手挡掉字母与符号，
                        // 省得用户输进来再被校验打回。长度上限 11 位（中国大陆手机号）。
                        onValueChange = { input ->
                            account = input.filter(Char::isDigit).take(11)
                            // 一旦开始输入就清掉内联错误，避免「改了还是红」。
                            if (accountError && account.isNotEmpty()) accountError = false
                        },
                        placeholder = "手机号",
                        leadingIcon = Icons.Filled.Phone,
                        colors = colors,
                        isError = accountError,
                        supporting = if (accountError) {
                            {
                                Text(
                                    "请输入手机号",
                                    color = colors.errorColor,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        } else {
                            null
                        },
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Next,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(FieldGap))

                    // 验证码：输入在左，竖线右侧是「发送验证码」
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AuthUnderlineField(
                            value = code,
                            onValueChange = { code = it.take(6) },
                            placeholder = "验证码",
                            // 需求：验证码图标改成信封样式。
                            leadingIcon = Icons.Filled.MailOutline,
                            colors = colors,
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Next,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(16.dp))
                        Box(
                            Modifier
                                .width(1.dp)
                                .height(30.dp)
                                .background(colors.hairline)
                        )
                        Spacer(Modifier.width(16.dp))
                        // 「发送验证码」这一格用固定宽度：转圈、原文、倒计时三种形态都居中在这里面，
                        // 分界线因此不会随内容宽窄左右移动。原因详见 [SendCodeSlotWidth]。
                        Box(
                            Modifier.widthIn(min = SendCodeSlotWidth),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            if (sendState.sending) {
                                SendCodeLoading(colors = colors)
                            } else {
                                AuthTextLink(
                                    text = if (sendState.secondsLeft > 0) {
                                        "${sendState.secondsLeft}s 后重发"
                                    } else {
                                        "发送验证码"
                                    },
                                    color = colors.accent,
                                    enabled = sendState.secondsLeft == 0,
                                    onClick = {
                                        // 只要有数字就行（输入框已限制只能输数字），不强求 11 位。
                                        if (account.isNotEmpty()) {
                                            // 倒计时与转圈一起启动；「已发送」提示由状态机在
                                            // 转圈结束后回调，这里不再直接弹。
                                            accountError = false
                                            // 「验证码测试」开启时才真正生成 + 通知（未开启是空操作）。
                                            devCode.send()
                                            sendState.onStart()
                                        } else {
                                            // 没填则显示内联红色错误（下划线 + 下方红字），且**不启动倒计时**，
                                            // 否则用户会立刻被 60 秒锁住，连改输的机会都没有。
                                            accountError = true
                                        }
                                    }
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(FieldGap))

                    // ---- 昵称 ----
                    //
                    // 三种状态（优先级从高到低）：
                    // ① 含中英数以外的字符 → 像手机号那样变红报错（需求）；
                    // ② 合法且查重中 → 右侧转小圈；
                    // ③ 查重通过 → 右侧显示「昵称可使用」。
                    AuthUnderlineField(
                        value = nickname,
                        // 只做长度截断，**不**在这里过滤字符：
                        // 需求要求「填了非法字符就变红报错」，若直接过滤掉，
                        // 用户会看到「打了字却没上去」，反而不知道哪里错了。
                        onValueChange = { nickname = it.take(NICKNAME_MAX_LENGTH) },
                        placeholder = "昵称（中文 / 英文 / 数字）",
                        leadingIcon = Icons.Filled.Person,
                        colors = colors,
                        isError = nicknameIllegal,
                        supporting = when {
                            nicknameIllegal -> {
                                {
                                    Text(
                                        "昵称只能包含中文、英文和数字",
                                        color = colors.errorColor,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }

                            nicknameState.available -> {
                                {
                                    Text(
                                        "昵称可使用",
                                        color = colors.accent,
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }

                            else -> null
                        },
                        trailing = if (nicknameState.checking) {
                            {
                                CircularProgressIndicator(
                                    color = colors.accent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        } else {
                            null
                        },
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Next,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(Modifier.height(FieldGap))

                    // ---- 性别（男 / 女 二选一）----
                    GenderPicker(
                        selected = gender,
                        onSelect = { gender = it },
                        colors = colors
                    )

                    Spacer(Modifier.height(FieldGap))

                    AuthUnderlineField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "设置密码",
                        leadingIcon = Icons.Filled.Lock,
                        colors = colors,
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        visualTransformation = if (passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        trailing = {
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .authPressable(
                                        onClick = { passwordVisible = !passwordVisible },
                                        pressedScale = 0.92f
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = if (passwordVisible) {
                                        Icons.Filled.Visibility
                                    } else {
                                        Icons.Filled.VisibilityOff
                                    },
                                    contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                                    tint = colors.muted,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    )

                    Spacer(Modifier.height(28.dp))

                    AuthPrimaryButton(
                        text = "注册",
                        colors = colors,
                        enabled = canRegister,
                        // 2026-10-25 需求：注册中只显示**浅色小圈、不显示文字**
                        // （时长在 ViewModel 里随机取）；按钮保持主色底（2026-10-22 需求 15）。
                        loading = registering,
                        hideTextWhenLoading = true,
                        // 2026-10-26 需求：转圈时按钮呈"按下灰"（浅色主题下也压得住）。
                        loadingGray = true,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            // `canRegister` 已经保证性别非空，这里用 `!!` 之外的写法只是为了让
                            // 智能转换生效（`gender` 是 `var`，编译器无法跨 lambda 保持非空推断）。
                            val picked = gender ?: return@AuthPrimaryButton
                            authViewModel.register(
                                phone = account,
                                nickname = nickname,
                                gender = picked,
                                // 验证码一并交给 ViewModel：开发者选项「验证码测试」开启时，
                                // 它会与「发送验证码」记下的六位数字比对；关闭时不看这个值。
                                credential = code
                            )
                        }
                    )

                    Spacer(Modifier.height(24.dp))
                }

                // ---- 底部协议行（始终贴底）----
                ConsentRow(
                    checked = agreed,
                    onCheckedChange = { agreed = it },
                    colors = colors,
                    onOpenAgreement = onOpenAgreement,
                    onOpenPrivacy = onOpenPrivacy
                )
            }
        }

        AuthToast(
            message = toast,
            colors = colors,
            modifier = Modifier.align(Alignment.Center)
        )
    }
}

/**
 * 性别选择：下拉单选（2026-10-25 需求：由两枚胶囊改为下拉，且整行比其它字段更矮）。
 *
 * ## 为什么从胶囊改成下拉
 *
 * 需求原文「性别改为下拉选择」。下拉的好处是这一行不再占据两个胶囊的横向空间，
 * 「未选择」态也只需要一处文字（右侧显示「请选择」），视觉与其余下划线字段更统一。
 *
 * ## 为什么不用 `ExposedDropdownMenuBox`
 *
 * 那套组件按 OutlinedTextField 的观感设计（描边盒子），与本页**下划线式**表单
 * （[AuthUnderlineField]）风格冲突；这里按下划线字段同款手写一行 + 锚定
 * `DropdownMenu`（必须包在锚点 [Box] 里，否则菜单按父节点定位跑到屏幕左上角——
 * 3.0.7 轮踩过的坑）。
 *
 * ## 高度
 *
 * [GENDER_ROW_HEIGHT] 取 44dp，**窄于**其余字段的 [FieldRowHeight]（52dp）
 * （需求：「缩窄性别一栏的高度」）——下拉行右侧只有文字+箭头，不需要输入框那么高。
 */
@Composable
private fun GenderPicker(
    selected: String?,
    onSelect: (String) -> Unit,
    colors: AuthColors
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(GENDER_ROW_HEIGHT)
                .authPressable(onClick = { expanded = true }),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Wc,
                contentDescription = null,
                tint = colors.muted,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text = "性别",
                color = colors.onPage,
                fontSize = 17.sp
            )
            Spacer(Modifier.weight(1f))

            // ⚠️ 锚点只框住右侧这组「当前值 + 箭头」，**不能**框整行。
            //
            // DropdownMenu 的位置是相对它的**直接父级**算的。v1.1.92 之前把
            // Box 套在整行外面（Row 是 fillMaxWidth），于是菜单从"整行的左边缘"
            // 往下弹——屏幕上看到的就是菜单飘到屏幕左侧、与「请选择」完全脱节
            // （2026-10-26 用户报「性别选项弹窗位置错误」，模拟器已复现）。
            // 框住右半边后，菜单自然贴在这一小块的左下角，视觉上就是下拉。
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = when (selected) {
                            GENDER_MALE -> "男"
                            GENDER_FEMALE -> "女"
                            else -> "请选择"
                        },
                        color = if (selected == null) colors.muted else colors.onPage,
                        fontSize = 15.sp
                    )
                    Icon(
                        imageVector = Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        tint = colors.muted,
                        modifier = Modifier.size(22.dp)
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("男") },
                        onClick = {
                            expanded = false
                            onSelect(GENDER_MALE)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("女") },
                        onClick = {
                            expanded = false
                            onSelect(GENDER_FEMALE)
                        }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(thickness = 1.dp, color = colors.fieldLine)
    }
}

/** 性别取值：与 `AuthRepository` 里写入 DataStore 的字符串一致。 */
private const val GENDER_MALE = "male"
private const val GENDER_FEMALE = "female"

/** 性别下拉行的高度（比 [FieldRowHeight] 窄，见 [GenderPicker] 的 KDoc）。 */
private val GENDER_ROW_HEIGHT = 44.dp

/**
 * 相邻输入字段之间的垂直间距。
 *
 * 原先「手机号 → 验证码」是 26dp、其余是 16dp，同一张表单里两种节奏；
 * 加上各输入框自身行高不固定，看起来就是「手机号和验证码之间挤、别处又松」。
 * 现在**行高固定（`FieldRowHeight`）+ 间距统一（这里）**，四行字段完全等距。
 *
 * 取 20dp 而不是沿用 16dp：用户反馈原来的间距偏窄，20dp 在 52dp 行高下更舒展。
 */
private val FieldGap = 20.dp

/** 注册成功后「注册成功」提示的停留时长，随即返回上一页。 */
private const val REGISTER_TOAST_MS = 1200L

/**
 * 底部协议行：勾选框 + 一句话，句中的两个书名号是**真正的行内链接**。
 *
 * 用 `buildAnnotatedString` 而不是四个并排的 `Text`：整句能自然换行，
 * 窄屏上不会把《隐私政策》挤出屏幕。
 */
@Composable
private fun ConsentRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    colors: AuthColors,
    onOpenAgreement: () -> Unit,
    onOpenPrivacy: () -> Unit
) {
    val linkStyles = TextLinkStyles(
        style = SpanStyle(
            color = colors.accent,
            textDecoration = TextDecoration.Underline
        )
    )
    val sentence = buildAnnotatedString {
        append("登录注册表示同意")
        withLink(
            LinkAnnotation.Clickable(
                tag = "agreement",
                styles = linkStyles,
                linkInteractionListener = LinkInteractionListener { onOpenAgreement() }
            )
        ) {
            append("《用户协议》")
        }
        append("、")
        withLink(
            LinkAnnotation.Clickable(
                tag = "privacy",
                styles = linkStyles,
                linkInteractionListener = LinkInteractionListener { onOpenPrivacy() }
            )
        ) {
            append("《隐私政策》")
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = colors.accent,
                checkmarkColor = colors.onAccent,
                uncheckedColor = colors.muted
            )
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = sentence,
            color = colors.muted,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
    }
}
