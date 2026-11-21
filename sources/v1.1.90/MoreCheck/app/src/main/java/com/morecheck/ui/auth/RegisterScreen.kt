package com.morecheck.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.morecheck.data.cloud.CloudAuthClient
import com.morecheck.data.cloud.CloudException
import com.morecheck.data.cloud.OtpChallenge
import com.morecheck.data.cloud.friendlyCloudMessage
import com.morecheck.di.appContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    /** 从登录页带过来的手机号（新号码被引导来注册时预填，省得重打一遍）。 */
    initialPhone: String = ""
) {
    val colors = rememberAuthColors()
    val context = LocalContext.current
    val authClient = remember(context) { context.appContainer.cloudAuthClient }
    val scope = rememberCoroutineScope()

    var account by rememberSaveable { mutableStateOf(initialPhone.filter(Char::isDigit).take(11)) }
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

    // 「发送验证码」的真实状态（v1.1.91 起接云端，与登录页同一套口径）。
    var sending by remember { mutableStateOf(false) }
    var secondsLeft by remember { mutableStateOf(0) }
    var challenge by remember { mutableStateOf<OtpChallenge?>(null) }

    // 冷却倒计时：到 0 自动停。
    LaunchedEffect(secondsLeft) {
        if (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
    }

    // 手机号为空时点「发送验证码」的内联错误提示；一输入就清掉。
    var accountError by remember { mutableStateOf(false) }

    // 昵称查重：合法转约 1 秒小圈 → 提示「昵称可使用」；含非法字符立刻报红。
    val nicknameState = rememberNicknameCheckState(nickname)
    val nicknameIllegal = nicknameHasIllegalChar(nickname)

    // 是否正在提交注册（按钮转圈）。
    var registering by remember { mutableStateOf(false) }

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
                            if (sending) {
                                CircularProgressIndicator(
                                    color = colors.accent,
                                    strokeWidth = 2.dp,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                AuthTextLink(
                                    text = if (secondsLeft > 0) {
                                        "${secondsLeft}s 后重发"
                                    } else {
                                        "发送验证码"
                                    },
                                    color = colors.accent,
                                    enabled = secondsLeft == 0,
                                    onClick = {
                                        if (account.isEmpty()) {
                                            // 没填则显示内联红色错误，且**不启动倒计时**，
                                            // 否则用户会立刻被 60 秒锁住，连改输的机会都没有。
                                            accountError = true
                                            return@AuthTextLink
                                        }
                                        val badFormat = runCatching {
                                            CloudAuthClient.requireMainlandPhone(account)
                                        }.exceptionOrNull() as? CloudException
                                        if (badFormat != null) {
                                            toast = friendlyCloudMessage(badFormat.error)
                                            return@AuthTextLink
                                        }
                                        accountError = false
                                        sending = true
                                        scope.launch {
                                            try {
                                                challenge = authClient.sendCode(account)
                                                // 只有**真的发出去了**才开始冷却。
                                                secondsLeft = SEND_COOLDOWN_SECONDS
                                                toast = "验证码已发送，请查看短信"
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: CloudException) {
                                                toast = friendlyCloudMessage(e.error)
                                            } catch (e: Exception) {
                                                toast = "发送失败：${e.message ?: "未知错误"}"
                                            } finally {
                                                sending = false
                                            }
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
                        // 注册中：按钮变灰 + 小圈转 3~5 秒（时长在 ViewModel 里随机取）。
                        loading = registering,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = {
                            // `canRegister` 已经保证性别非空，这里用 `!!` 之外的写法只是为了让
                            // 智能转换生效（`gender` 是 `var`，编译器无法跨 lambda 保持非空推断）。
                            val picked = gender ?: return@AuthPrimaryButton
                            val current = challenge
                            if (current == null) {
                                toast = "请先获取验证码"
                                return@AuthPrimaryButton
                            }
                            if (current.isExistingUser) {
                                // 已注册的号码不该在这里「再注册一次」：直接引导回登录，
                                // 避免出现「注册成功」但其实只是登进了老账号的错觉。
                                toast = "该手机号已注册，请用密码或验证码登录"
                                return@AuthPrimaryButton
                            }
                            if (password.length < MIN_PASSWORD_LENGTH) {
                                toast = "密码至少 $MIN_PASSWORD_LENGTH 位"
                                return@AuthPrimaryButton
                            }
                            registering = true
                            scope.launch {
                                try {
                                    // 注册 = 校验验证码 + 建号 + 设置密码，一次请求闭环。
                                    val session = authClient.verifyCode(
                                        current,
                                        code,
                                        password = password
                                    )
                                    val cloudName = try {
                                        authClient.me().name
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        null
                                    }
                                    // 先落本地资料再落地登录态：onCloudLoginSucceeded 只在
                                    // 本地昵称为空时才用 cloudName，顺序反了就会被空值占位。
                                    context.appContainer.settingsRepository.setNickname(nickname)
                                    context.appContainer.settingsRepository.setGender(picked)
                                    context.appContainer.authRepository.onCloudLoginSucceeded(
                                        phone = session.phone ?: account,
                                        cloudName = cloudName
                                    )
                                    toast = "注册成功"
                                    delay(REGISTER_TOAST_MS)
                                    onRegistered()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: CloudException) {
                                    toast = friendlyCloudMessage(e.error)
                                } catch (e: Exception) {
                                    toast = "注册失败：${e.message ?: "未知错误"}"
                                } finally {
                                    registering = false
                                }
                            }
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
 * 性别选择：男 / 女 二选一。
 *
 * 用两个胶囊按钮而不是下拉框：只有两个选项，摊开来一次点选比「点开 → 再选」少一步，
 * 而且能直接看出「还没选」（两个都是未选中态），这正好对应注册按钮的可点条件。
 *
 * 视觉与「设置 → 深色模式」的 [androidx.compose.material3.SingleChoiceSegmentedButtonRow]
 * 保持一致，但这里手写两个胶囊：分段按钮会在选中项里额外画一个勾，
 * 而本页已在昵称处用「转圈 / 可使用」表达状态，再出现勾会让页面信号过多。
 */
@Composable
private fun GenderPicker(
    selected: String?,
    onSelect: (String) -> Unit,
    colors: AuthColors
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.height(FieldRowHeight),
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
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GenderChip(
                    label = "男",
                    active = selected == GENDER_MALE,
                    colors = colors,
                    onClick = { onSelect(GENDER_MALE) }
                )
                GenderChip(
                    label = "女",
                    active = selected == GENDER_FEMALE,
                    colors = colors,
                    onClick = { onSelect(GENDER_FEMALE) }
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        HorizontalDivider(thickness = 1.dp, color = colors.fieldLine)
    }
}

/** 性别胶囊：选中时主题色实底 + `onAccent` 文字，未选中是描边 + 次要色文字。 */
@Composable
private fun GenderChip(
    label: String,
    active: Boolean,
    colors: AuthColors,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(999.dp)
    Box(
        modifier = Modifier
            .height(34.dp)
            .clip(shape)
            .background(if (active) colors.accent else colors.secondary)
            .then(
                if (active) Modifier else Modifier.border(1.dp, colors.hairline, shape)
            )
            .authPressable(onClick = onClick, pressedScale = 0.94f)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (active) colors.onAccent else colors.onPage,
            fontSize = 14.sp
        )
    }
}

/** 性别取值：与 `AuthRepository` 里写入 DataStore 的字符串一致。 */
private const val GENDER_MALE = "male"
private const val GENDER_FEMALE = "female"

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

/** 「发送验证码」成功后的冷却秒数（云端重发限频约 1 分钟）。 */
private const val SEND_COOLDOWN_SECONDS = 60

/** 登录密码最短长度，与服务端约束对齐，避免提交后才被拒。 */
private const val MIN_PASSWORD_LENGTH = 6

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
