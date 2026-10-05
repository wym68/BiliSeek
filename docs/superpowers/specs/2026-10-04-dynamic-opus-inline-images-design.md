# 长图文动态展开：全文加载、图片按原位置显示、长按选字

日期：2026-10-04
范围：`core:network` 的 `dto/DynamicDto.kt`、`dto/DynamicModulesCompatSerializer.kt`、`DynamicFeed.kt`、`DynamicDetail.kt`；`app` 的 `ui/DynamicScreen.kt`、`ui/UpSpaceScreen.kt`、`ui/DynamicBodyPolicy.kt`（新增）、`ui/richtext/RichText.kt`。

## 背景（用户原话）

> 现在的动态展示还是有点问题。看这个动态，这个动态内容比较多，同时在文字中间穿插了图片。在展开时，图片没有正常的渲染，而且文字没有加载全。

补充（选方案时）：

> 此外，现在单点展开的动态文字会自动折叠。我还想长按时进行文字选择。

截图里展开后的正文中间是一行行「[图片]」，末尾是「…」，卡片上一张图都没有。

## 现状与原因

1. **文字没加载全**：展开时拉两份详情——web 详情（和动态流一样，只有截断的摘要：图片位置写成「[图片]」，末尾「…」）和 opus 详情（完整段落）。挑「内容最多」的候选时，`detailContentScore` 对图文只算 `opus.pics 张数 + opus.summary 长度`；opus 详情的全文被归一化进了 `desc`，`summary` 是空的，于是它的得分只有图片张数，永远输给截断摘要，全文被丢掉。（bilipai 的归一化会把全文同时写进 summary，并且带段落的候选直接加 10 万分优先；移植时漏了。）
2. **图片没显示**：解析 opus 详情时文字段落拼成一整段、图片另收一组，图片在原文里的位置当场就丢了；展开态也只换文字，图片区仍是动态流里的预览图。这条动态在动态流里没有预览图（`opus.pics` 为空，图片只在正文里），所以一张都不显示。
3. **修好 1 之后会冒出来**：opus 详情的 `item.type` 可能是数字（bilipai 有对应用例 `"type": 1`），`parseDynamicItem` 认不出就返回 null，`mergeDetailItem` 抛错，展开显示「加载失败」。
4. **单击正文会收起**：展开后整段正文挂着 `clickable(onToggleExpand)`，点哪都收起；长按松手也算一次点击，同样收起，没法选字。

## 方案（用户已选：按原文顺序穿插）

### 网络层（`core:network`）

- 新增公开类型（:app 要画）：
  - `DynamicBlock`（sealed）：`Text(text)` / `Images(pics: List<DynamicPic>)`；
  - `DynamicPic(url, width, height)`，宽高缺失为 0。
- `DynamicOpus` 加 `@Transient blocks`：只在 opus 详情的段落归一化时填，不参与序列化。
- `DynamicModulesCompatSerializer` 按原文顺序收段落：
  - 文字段（heading / text / list）连续的合成一段，用换行连接，和以前拼整段的样子一致；
  - 图片段（`pic.pics` / `pic.url` / `pics`）一段一个 `Images`，带宽高；
  - 分割线图（`line.pic`）是装饰，不进正文段落（仍留在平铺的 `pics` 里，和以前一样）。
- `DynamicEntry` 加 `body: List<DynamicBlock>`（默认空），`parseDynamicItem` 从 `major.opus.blocks` 取。
- `detailContentScore`：带段落的候选得 `100_000 + 文字长度 + 图片张数`，总是优先于只有摘要的候选。
- `mergeDetailItem`：
  - 详情带段落时正文以详情为准（feed 摘要因为「[图片]」占位可能反而更长）；
  - 详情 `type` 不是认得的字符串时按 feed 卡片（seed）的类型解析，没有 seed 按 OPUS。

### 界面（`app`）

- 展开且拉到的详情带段落时，正文区按段落顺序画（`DynamicBody`）：
  - 文字段照旧（表情、话题）；
  - 一段一张图：铺满卡片宽度、按原图比例；缺宽高按 4:3；特别长的图最高到宽的 2 倍（约一屏），多出的从下面裁掉，点开看全图；
  - 一段多张图：九宫格（`DrawGrid`）；
  - 点图打开预览，正文里所有图按顺序左右翻。
- 正文里有图时，卡片下方不再重复画动态流的预览图；正文里没图时照旧。
- 没拉到段落（只有摘要、被风控冷却等）时退回现在的整段文字。
- 展开后单击正文不再收起；「收起」改成正文下方右侧单独一行（和折叠态的「…展开」对称，选中全部复制时也不会把「收起」带上）。
- 动态正文长按可选中复制（`SelectionContainer`）：折叠态、加载中、失败、展开后都可以；链接、话题照样能点。
- `RichText` 的 `suffix` 参数只有动态「收起」在用，改完没有调用方了，删掉。

判断放在 `ui/DynamicBodyPolicy.kt` 的纯函数：`expandedBody`、`bodyImageUrls`、`bodyImageOffsets`、`inlineImageAspect`。

## 不做

- 折叠态（动态流摘要）里的「[图片]」占位不去：它提示这里有图，展开后就是真图。
- 标题 / 引用 / 代码 / 链接卡片段落不单独排版：标题、引用当普通文字；链接卡片（`link_card`）和以前一样不显示。
- 转发动态里原动态的长图文：展开仍是整段文字（转发走 web 详情，不拉 opus 详情）。

## 风险

- opus 详情真实返回的形状无法在自动化里验证（不能请求 B 站）：数字 `type`、图片缺宽高都已兜底；真机上要看几条长图文、普通图文（九宫格）、纯文字动态展开是否正常。
- 普通图文（文字较长 + 九宫格）展开后，九宫格从卡片底部挪到正文段落里显示，位置和 B 站一致。

## 测试与验证

- 先写测试并确认失败（含复现：段落候选输给截断摘要），再实现：
  - `core:network` `DynamicDetailParseTest`：段落顺序、评分优先、合并取详情全文、数字 type；
  - `app` `DynamicBodyPolicyTest`：展开判定、图片摊平与下标、宽高比。
- `DynamicCardClickRegionsUiTest` 加「单击展开正文不收起」「段落正文显示文字」，只编译核对。
- `./gradlew test assembleDebug :app:compileDebugAndroidTestKotlin`；不装机，等用户开口。
