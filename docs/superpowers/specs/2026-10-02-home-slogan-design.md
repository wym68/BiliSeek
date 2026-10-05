# 首页新用户引导：slogan 放大成引文式艺术字

日期：2026-10-02
范围：`app` 模块的首页空状态（`ui/HomeScreen.kt`）、新增组件 `ui/components/HomeSlogan.kt`；`BiliSeekWordmark.kt` 里字体族的可见性。
承接 [home-header-polish](2026-10-02-home-header-polish-design.md)：标题已换成 DM Serif Display，这次让 slogan 和标题成套。

## 背景

真机首次安装后的首页（用户反馈）：红框里的 slogan——英文「Seek and you shall find.」16sp、中文「探索你的 B 站」12sp，系统字体，
字太小，和标题（34sp 衬线艺术字）不在一个层级，视线不会停在这里。要求：字号调大，设计艺术字样式，给几个选择。

## 选型过程

在浏览器里按真机的深色配色、1dp = 1px 的比例渲染，对比了四个方案（左边距都改成 16dp，中文副句统一 15sp、字距 3sp）：

- A 衬线 + 强调色关键词（DM Serif，「find.」斜体加强调色，30sp 一行）；
- B 渐变斜体（36sp 两行，主题色渐变到主题第三色）；
- C 引文（大引号 + 斜体 + 短横线引出中文副句）；
- D 手写毛笔（Kaushan Script，需新增字体文件与 NOTICE 署名）。

用户选 C。随后追问：英文能否一行 → 30sp 一行放得下（约 302dp，可用宽度约 328dp 以上）；又问「为什么觉得英文往上倾斜」——
实测基线是平的（整行 284px 内高低差 0.0px，没有旋转），倾斜感来自斜体字形本身（DM Serif Display 斜体的字体元数据 `italicAngle = −12°`）。
并排对比了整句斜体 / 整句正体 / 正体加斜体「find.」之后，用户**定稿 C-1：整句斜体，30sp 一行**。

## 方案

新增 `HomeSlogan`（`ui/components/HomeSlogan.kt`），替换 `HomeEmptyState` 里那个两行 `Text` 的引导块。显示条件不变（无搜索历史且无播放热力数据）。

结构（颜色全取主题色，所以莫奈取色、深色模式、封面强调色都会带着它一起变）：

| 元素 | 样式 |
| --- | --- |
| 大引号「“」 | DM Serif Display 正体，120（dp 换算成 sp，**不随系统字体放大**），行高同字号，`primary` 30% 透明度；纯装饰，读屏忽略；相对内容区左上偏移 (−4dp, −4dp) |
| 英文「Seek and you shall find.」 | DM Serif Display **斜体**，30sp，行高 38sp，字距 −0.3sp，`onSurface`；一行，系统字体放大后放不下时自动折成两行 |
| 短横线 | 28 × 2dp，圆角 1dp，`primary`；与中文副句垂直居中，间距 10dp |
| 中文「探索你的 B 站」 | 15sp，行高 22sp，字距 3sp，`onSurfaceVariant`，系统字体 |

版面（相对搜索框底边 P）：`HomeEmptyState` 自带 8dp 上内边距，搜索框下方还有约 8dp（用现状截图量出：原引导块的文字顶离搜索框底边的距离，
扣掉原来的 32dp 内边距后得到），所以块顶在 P+16。效果图里引号文字框顶在 P+12、英文行框顶在 P+66、中文行框顶在 P+118、
「快捷入口」标题顶在 P+176，换算成：

- 块左右各 16dp（和标题、搜索框对齐；原来是 32dp）；
- 引号相对块内容区左上偏移 (−4dp, −4dp)；
- 英文 + 中文这一列：顶部内边距 50dp，英文与中文之间 14dp，底部内边距 20dp——块高 50 + 38 + 14 + 22 + 20 = 144dp，
  再经 `HomeEmptyState` 的 `spacedBy(16.dp)` 到「快捷入口」标题（P+176）。

行高对齐：三个文本都显式指定 `LineHeightStyle(Center, Trim.None)`，字形在行高里上下居中，与浏览器的半行距一致，效果图（HTML）和真机排版才对得上。
DM Serif Display 的 hhea / typo / win 行高指标一致（1036 / −335，无行距），浏览器与 Android 取值相同；
按这组指标算出的引号墨迹顶、英文基线位置，与效果图渲染的实测值相差不到 1px。

## 实现

- `BiliSeekWordmark.kt`：`private val DmSerifDisplay` → `internal val`（同模块复用）。不新增字体文件、不新增 NOTICE（OFL 条目 9 已涵盖正体与斜体）。
- 新增 `HomeSlogan.kt`：一个左右 16dp 的 `Box`，叠一个装饰引号 `Text` 与一列文字；无状态、无参数（除 `modifier`）。
- `HomeScreen.kt`：引导块换成 `HomeSlogan()`。

## 不做

- 不加打字机逐字出现等动效。
- 不动问候语、搜索框、热力图、快捷入口。
- 不抽 `*Policy`、不新增单测：没有判断逻辑可抽（同 home-header-polish），现有测试也不钉死 slogan 文案。

## 风险

- 引号与英文行的相对位置是按字体指标和浏览器效果图推算的，**没有在真机上渲染过**；偏差多半在几 dp。真机上若觉得引号离英文太近或太远，
  调 `HomeSlogan` 里引号的偏移和英文列的顶部内边距即可。
- 一行 30sp 约 302dp 宽；系统字体放大到约 1.1 倍以上会折成两行，不会溢出。
- 块高 144dp，比原来（约 104dp）多 40dp，快捷入口整体下移；首页是可滚动列，小屏手机不会被截断。

## 测试与验证

- 自动化：`./gradlew test assembleDebug`（全离线）确认现有测试不受影响、debug 编译通过。
- 真机（由用户）：见手动测试指南步骤 1「首屏」。

## 文档

- `docs/05-功能优化篇.md`：新增 §13.32，总览表加一行。
- `docs/BiliSeek-手动测试指南.md`：步骤 1「首屏」检查点改写。
