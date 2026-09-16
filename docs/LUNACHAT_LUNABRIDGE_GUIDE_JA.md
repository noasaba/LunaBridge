# LunaChat 4 + LunaBridge 統合導入・API利用ガイド

この文書は、LunaChat 4 と LunaBridge を使って Minecraft と Discord のチャットを連携する管理者・プラグイン開発者向けの統合ガイドです。導入方法、日常的な使用方法、Integration API v1 の使い方、各プラグインの責任範囲、運用上の注意を1か所にまとめています。

## 1. 対象バージョン

| 対象 | バージョン |
| --- | --- |
| LunaChat Paper / Velocity | `4.0.0-SNAPSHOT` |
| LunaChat Integration API | `1.0.0-SNAPSHOT`（API major 1） |
| LunaChat network wire | `2`（`lunachat:network_v2`） |
| LunaBridge Paper / Velocity | `0.3.0-beta.20` |
| LunaBridge config schema | `5` |
| Java | `25` |
| Paper API | `26.2` |
| Velocity API | `4.1.x` |

LunaChat製品、公開Integration API、内部network wire、設定schemaは別々にバージョン管理されます。API majorが同じでもwire番号が異なるLunaChat PaperとVelocityは相互接続できません。

## 2. 最重要の責任分界

原則は次の一文です。

> LunaChatはMinecraftチャットとMinecraftネットワークを管理し、LunaBridgeはDiscord連携だけを管理する。

| 機能 | LunaChat | LunaBridge |
| --- | --- | --- |
| チャンネル作成、参加、権限、BAN、Mute | 担当 | 担当しない |
| NGワード、Japanize、最終表示文字列 | 担当 | 結果だけを受け取る |
| Paper上のチャット描画 | 担当 | 担当しない |
| Paper間・Velocity間の通信 | 担当 | 担当しない |
| network認証、暗号化、ACK、再送、重複排除 | 担当 | 担当しない |
| Discord Bot接続とtoken | 担当しない | 担当 |
| Discordチャンネルとの対応付け | 安定したChannelIdを提供 | 対応を保存 |
| MinecraftからDiscordへの転送 | 確定済みメッセージを通知 | Discordへ送信 |
| DiscordからMinecraftへの投稿 | 検証、変換、採用、描画 | APIへ投稿を依頼 |
| Discordの`/players`、`!p`、参加通知 | 担当しない | 担当 |

LunaBridgeはLunaChatのレガシーチャットイベントやplugin messageを横取りしません。LunaChatが最終採用したメッセージだけをFrozen Integration API v1経由で扱います。そのため、NGワード処理やJapanizeより前の未確定文字列がDiscordへ送られることはありません。

## 3. 選べる構成

### 3.1 Paper単体構成

1台のPaperだけで運用する構成です。

```text
Discord
   ↕ LunaBridge-Paper-Standalone
Paper
   └ LunaChat-Paper（STANDALONE_AUTHORITY）
```

Paperの`plugins`へ次の2本を入れます。

- `LunaChat.jar`
- `lunabridge-paper-standalone-0.3.0-beta.20.jar`

この構成ではLunaChatの`integration.sharePass`を空にします。

### 3.2 Velocityネットワーク構成

複数のPaperをVelocity配下で運用する構成です。

```text
Discord
   ↕ LunaBridge-Velocity
Velocity
   └ LunaChat-Velocity（NETWORK_AUTHORITY）
        ↕ 認証済みLunaChat network wire v2
Paper backend A/B/...
   └ LunaChat-Paper（NETWORK_EDGE）
```

Velocityの`plugins`へ次の2本を入れます。

- `LunaChat-Velocity.jar`
- `lunabridge-velocity-0.3.0-beta.20.jar`

各Paper backendの`plugins`へ次の1本だけを入れます。

- `LunaChat.jar`

network backendへ`lunabridge-paper-standalone`を入れてはいけません。`NETWORK_EDGE`は意図的に外部投稿・メッセージ監視機能を公開しないため、LunaBridge Paperは起動を拒否します。

## 4. 導入手順

### 4.1 Discord Botの準備

1. Discord Botを作成し、Message Content Intentを有効にします。
2. 対象チャンネルでBotにチャンネル閲覧・メッセージ送信権限を与えます。
3. Bot tokenは設定ファイルへ直接書かず、専用ファイルへ1行だけ保存します。
4. LunaBridgeの`discord.token-file`にそのファイルへのパスを設定します。

tokenファイルには前後の空白を除いたtokenを1行だけ入れてください。複数行のファイルは拒否されます。相対パスはLunaBridgeのplugin data directoryから解決されます。

### 4.2 LunaChatの外部投稿許可

Discordから投稿させるLunaChatチャンネルのYAMLで、次を有効にします。

```yaml
schema_version: 1
channel_id: "550e8400-e29b-41d4-a716-446655440000"
accepts_external_messages: true
```

- `channel_id`はLunaChatが生成する永続UUIDです。手動で名前へ置き換えないでください。
- チャンネル名とaliasは検索・表示用です。永続的な配送キーは`channel_id`です。
- 初回schema移行時、LunaChatは旧チャンネルデータを`migration-backup-v0/channels`へ退避します。
- 外部投稿を許可しないチャンネルは`false`のままにします。

### 4.3 Paper単体のLunaChat設定

`plugins/LunaChat/config.yml`:

```yaml
integration:
  sharePass: ''
  maxPending: 256
  dedupCapacity: 4096
```

空の`sharePass`はstandalone authorityを意味します。

### 4.4 VelocityネットワークのLunaChat設定

Velocityの`plugins/lunachat/network.properties`:

```properties
schema=1
sharePass=replace-with-a-unique-passphrase
maxPending=256
dedupCapacity=4096
```

各Paperの`plugins/LunaChat/config.yml`:

```yaml
integration:
  sharePass: 'replace-with-a-unique-passphrase'
  maxPending: 256
  dedupCapacity: 4096
```

Velocityと全Paperへ同じ`sharePass`を設定します。

- 最低12文字です。
- 実際の256-bit keyはPBKDF2-HMAC-SHA256で導出されます。
- `role`、`serverId`、Base64形式の`sharedSecret`は新規設定では不要です。
- PaperのサーバーIDはVelocityが実際の`ServerConnection`から決定し、認証済みHELLO/READY交換でPaperへ通知します。
- plugin messagingはオンラインプレイヤーをcarrierとして使います。空のbackendでは、プレイヤーが接続するまで初回READYや配送が行われない場合があります。

起動順はVelocity、各Paperの順を推奨します。wire変更時は全LunaChatノードを停止して同時に更新し、旧wireと新wireを混在させないでください。

### 4.5 LunaBridgeの設定

Velocityでは`plugins/lunabridge-velocity/config.properties`を使います。

```properties
config-version=5
discord.token-file=/secure/path/discord-token
discord.text-commands.enabled=true
discord.commands.players.mode=both
discord.commands.players.text-trigger=!p
discord.minecraft-chat-format=[{channel}] {username}: {message}{japanized}
discord.external-display-name-format=Discord:{username}
```

Paper単体では`plugins/LunaBridge-Paper-Standalone/config.yml`を使います。

```yaml
config-version: 5
discord:
  token-file: "/secure/path/discord-token"
  minecraft-chat-format: "[{channel}] {username}: {message}{japanized}"
  external-display-name-format: "Discord:{username}"
```

inlineの`discord.token`も後方互換で利用できますが、`token-file`が優先されます。

### 4.6 DiscordチャンネルをLunaChatへ接続

Velocityコンソール、または`lunabridge.admin`権限を持つゲーム内プレイヤーから実行します。

```text
lunabridge setup <DiscordチャンネルID> <LunaChatチャンネル名またはalias>
```

例:

```text
lunabridge setup 1307767610976243722 global
```

`setup`は次の処理を行います。

1. LunaChat APIから名前またはaliasを検索します。
2. `accepts_external_messages=true`を確認します。
3. 安定した`ChannelId`を設定へ保存します。
4. 稼働中のDiscord connectorへ即時反映します。
5. Botが送信可能なら接続確認メッセージを1回キューへ入れます。

`OK Discord setup test queued`は通常チャットへ`hello`を投稿したという意味ではありません。設定したDiscordチャンネルへ「このチャンネルをLunaChatへ対応付けた」という確認メッセージを送る処理が受理されたという意味です。

Velocityでは`setup`と`doctor`の両方に`lunabridge.admin`が必要です（コンソールは常に許可されます）。権限のないプレイヤーは実行できません。`doctor`はtokenの値を表示せず、設定済みかどうかだけを報告します。

systemdでVelocityを起動していて後から標準入力コンソールを利用できない場合は、LuckPermsなどで管理者へ権限を付与し、ゲーム内から実行します。

```text
/lp user <Minecraftプレイヤー名> permission set lunabridge.admin true
/lunabridge doctor
/lunabridge setup <DiscordチャンネルID> <LunaChatチャンネル名またはalias>
```

## 5. 日常的な使い方

### 5.1 MinecraftからDiscord

プレイヤーは通常どおりLunaChatチャンネルで発言します。LunaChatが権限、NGワード、Japanize、event変換を終えて最終採用した後、LunaBridgeが対応するDiscordチャンネルへ送ります。

既定表示:

```text
[global] Tomochan10: test (手st)
```

書式は`discord.minecraft-chat-format`で変更できます。

| placeholder | 内容 |
| --- | --- |
| `{channel}` | LunaChatチャンネル名 |
| `{username}` | プレイヤー表示名 |
| `{message}` | Japanize結果を除いた元メッセージ |
| `{japanized}` | 既定の1行Japanize suffix。存在する場合は先頭空白と括弧を含み、存在しない場合は空文字 |

二重波括弧の`{{message}}`形式も使用できます。

`{message}`と`{japanized}`の分離は、LunaChatの既定値
`japanizeLine1Format: '%msg &6(%japanize)'`を対象にしています。このLunaChat書式を変更した場合、確定済み`content`から元文と変換結果を一般的に識別できないため、全体が`{message}`へ入り`{japanized}`は空になることがあります。

例:

```properties
discord.minecraft-chat-format={username} @ {channel} > {message}{japanized}
```

Minecraftのlegacy color/decoration codeはDiscord表示時だけ除去されます。LunaChat内部の確定メッセージやMinecraft側の描画は変更されません。Discord mentionは抑制されるため、プレイヤーが`@everyone`などを入力してもmentionとして発火しません。

### 5.2 DiscordからMinecraft

対応付け済みDiscordチャンネルで通常メッセージを送ると、LunaBridgeがLunaChatの`publishExternal`へ依頼します。LunaChatは外部投稿許可、重複、期限、容量を検証し、Paperで最終処理した内容をMinecraftへ描画します。

Discord投稿に画像attachmentがある場合、LunaBridgeは画像ごとのDiscord CDN URLを本文の末尾へスペース区切りで追加してMinecraftへ送ります。本文なしの画像投稿ではURLだけを送ります。画像以外のattachmentは転送しません。

外部発言者の表示は`discord.external-display-name-format`で設定します。既定値は`Discord:{username}`で、LunaChatの既存チャンネル書式と組み合わさり`Discord:NAME: message`となります。`{username}`はDiscordのeffective display nameです。Legacy装飾コード、改行、制御文字は除去されます。この設定はDiscordからMinecraftへの発言者表示だけに適用され、MinecraftからDiscordへの`discord.minecraft-chat-format`は変更しません。

Discord message IDが冪等性キーになるため、再試行されても同一メッセージを二重描画しません。外部originは最後まで保持され、Minecraft由来としてDiscordへ折り返されないためループを防止できます。

### 5.3 オンラインプレイヤー確認

Velocity版では次を利用できます。

- Discord slash command: `/players`
- text command: `!p`（既定値）

`discord.commands.players.mode`は`both`、`slash`、`text`を指定できます。text command全体は`discord.text-commands.enabled=false`で無効化できます。コマンドはLunaBridgeへ対応付け済みのDiscordチャンネルでだけ動作します。

### 5.4 参加・退出などの通知

LunaBridgeはstartup、shutdown、join/login、quit、first-login、server-switch通知を設定できます。Velocity版では必要に応じて通知先channel IDとfirst-login時のrole IDも指定できます。通知テンプレートは`{player}`または`{{player}}`などのplaceholderを利用します。

## 6. LunaChatの主なコマンド

`/lunachat`は`/ch`または`/lc`でも実行できます。

| コマンド | 用途 |
| --- | --- |
| `/ch join <channel>` | チャンネルへ参加 |
| `/ch leave` | 現在のチャンネルから退出 |
| `/ch list` | チャンネル一覧 |
| `/ch create <channel>` | チャンネル作成 |
| `/ch remove [channel]` | チャンネル削除 |
| `/ch info [channel]` | チャンネル情報 |
| `/ch invite <player> [force]` | プレイヤーを招待 |
| `/ch accept` / `/ch deny` | 招待を承認・拒否 |
| `/ch kick <player> [channel]` | チャンネルからkick |
| `/ch ban <player> [channel|minutes]` | BAN |
| `/ch pardon <player> [channel]` | BAN解除 |
| `/ch mute <player> [channel|minutes]` | 発言禁止 |
| `/ch unmute <player> [channel]` | Mute解除 |
| `/ch hide ...` / `/ch unhide ...` | チャンネルまたはプレイヤーの表示抑制・解除 |
| `/ch format [channel] <format...>` | チャンネル表示書式 |
| `/ch option <key=value...>` | チャンネルoption変更 |
| `/ch moderator [channel] <players...>` | moderator設定 |
| `/ch dictionary add|remove ...` | Japanize辞書管理 |
| `/ch reload` | LunaChat設定reload |
| `/ch help [user|mod|admin] [page]` | ヘルプ |
| `/tell <player> [message]` | 1対1メッセージ |
| `/reply [message]` または `/r` | 直前の相手へ返信 |
| `/japanize on|off` または `/jp` | Japanize切替 |

現在のVelocity authorityでは、チャンネルメッセージと外部投稿はnetwork routingされますが、membership、invite、mute/ban変更、`/tell`、`/r`の状態は完全なnetwork-wide commitへ移行していません。これらはbackend-localとして扱い、全backendで共有されたと案内しないでください。offline private message queueもありません。

## 7. LunaBridgeの管理コマンド

| コマンド | 用途 |
| --- | --- |
| `lunabridge setup <Discord channel ID> <LunaChat name/alias>` | channel対応を保存して即時反映 |
| `lunabridge doctor` | API、network、token、Discord gateway、mappingを診断 |
| `lunabridge unmap <Discord channel ID>` | 不要または無効なchannel対応を削除 |

Velocityではコンソールまたは`lunabridge.admin`権限を持つプレイヤーだけが実行できます。`setup`は設定を変更するため常にこの権限が必要です。`doctor`もDiscord tokenの設定状態やmappingを含む運用診断を出力するため、同じ管理権限が必要です。

`doctor`の判定:

- `OK`: 利用可能
- `WAIT`: 初期化・再接続・reload中
- `FAIL`: 設定不足、接続不能、不整合、停止状態

主な確認項目はAPI version/role、LunaChat network state、Discord token、gateway READY、全mappingのChannelIdと外部投稿許可です。

## 8. Integration API v1 開発者ガイド

### 8.1 依存関係

```text
com.github.ucchyocean:lunachat-api:1.0.0-SNAPSHOT
```

依存は`compileOnly`または`provided`にしてください。API JARをconsumer pluginへshadeしてはいけません。providerとconsumerが同じclass identityを共有できなくなるためです。API artifact自体はBukkit、Velocity、JDA、暗号・wire型へ依存しません。

### 8.2 runtime role

| role | 意味 | integration pluginの配置 |
| --- | --- | --- |
| `STANDALONE_AUTHORITY` | 単体Paperの権威ノード | Paper consumerを配置可能 |
| `NETWORK_AUTHORITY` | Velocity networkの権威ノード | Velocity consumerを配置可能 |
| `NETWORK_EDGE` | Velocity配下のPaper | observe/publish consumerを配置しない |

### 8.3 capability

- `QUERY_CHANNELS`
- `OBSERVE_ACCEPTED_MESSAGES`
- `PUBLISH_EXTERNAL_MESSAGES`
- `QUERY_NETWORK_STATUS`

API versionだけで機能を推測せず、使用前に必要なcapabilityをすべて確認してください。

### 8.4 PaperでのAPI取得

```java
RegisteredServiceProvider<LunaChatIntegrationApi> registration =
    Bukkit.getServicesManager().getRegistration(LunaChatIntegrationApi.class);
LunaChatIntegrationApi api =
    registration == null ? null : registration.getProvider();

if (api == null
        || api.apiVersion().major() != 1
        || api.runtimeRole() != RuntimeRole.STANDALONE_AUTHORITY
        || !api.capabilities().contains(Capability.QUERY_CHANNELS)
        || !api.capabilities().contains(Capability.OBSERVE_ACCEPTED_MESSAGES)
        || !api.capabilities().contains(Capability.PUBLISH_EXTERNAL_MESSAGES)) {
    throw new IllegalStateException("LunaChat authority is unavailable");
}
```

LunaChatはチャンネル初期化後にBukkit `ServicesManager`へAPIを登録し、shutdown前に解除します。

### 8.5 VelocityでのAPI取得

```java
Object instance = proxy.getPluginManager().getPlugin("lunachat")
    .flatMap(container -> container.getInstance())
    .orElse(null);

if (!(instance instanceof LunaChatApiProvider provider)) {
    throw new IllegalStateException("LunaChat provider is unavailable");
}

LunaChatIntegrationApi api = provider.current()
    .orElseThrow(() -> new IllegalStateException("LunaChat API is not ready"));

if (api.apiVersion().major() != 1
        || api.runtimeRole() != RuntimeRole.NETWORK_AUTHORITY) {
    throw new IllegalStateException("Incompatible LunaChat runtime");
}
```

Velocityのplugin IDは`lunachat`です。plugin instanceが`LunaChatApiProvider`を実装します。`current()`は初期化完了前とshutdown後にemptyになります。

### 8.6 チャンネル検索

永続済みのIDがある場合は`find(ChannelId)`を使います。初回setup UIなど、人が名前を入力する場面だけ`findByNameOrAlias`を使い、解決したIDを保存します。

```java
ChannelDescriptor channel = api.channels()
    .findByNameOrAlias("global")
    .orElseThrow();

if (!channel.acceptsExternalMessages()) {
    throw new IllegalStateException("External publishing is disabled");
}

String stableIdToPersist = channel.id().value();
```

一覧は`listVisibleToIntegration(new ChannelPageRequest(limit, cursor))`で取得します。`limit`は1から100、cursorはopaqueとして扱い、内容を解釈しないでください。

### 8.7 確定メッセージの監視

```java
Subscription subscription = api.messages().observeAcceptedMessages(message -> {
    if (message.origin().kind() == OriginKind.MINECRAFT) {
        relayToExternalService(message);
    }
});
```

`AcceptedMessage`はLunaChatの認可、filter、Japanize/event変換、最終文字列選択が完了し、local renderingする直前の論理メッセージです。全clientが実際に描画したことまでは保証しません。

主要field:

- `messageId`: 再送をまたいで不変な論理UUID
- `channelId`: 永続ChannelId
- `channelName`: 表示・診断用の現在名
- `origin`: `MINECRAFT`、`EXTERNAL`、`SYSTEM`とsource metadata
- `author`: `Player`、`External`、`System`のいずれか
- `sourceServerId`: 発生元サーバー
- `content`: LunaChatが最終確定した内容
- `createdAt` / `expiresAt`: 生成時刻と有効期限

observerはboundedかつ隔離されたexecutorで呼ばれます。listenerの例外はMinecraft chat deliveryを失敗させません。consumer disable時には必ず`Subscription.close()`を呼んでください。

### 8.8 外部メッセージの投稿

```java
ExternalMessageRequest request = new ExternalMessageRequest(
    channelId,
    new ExternalMessageIdentity("myplugin:discord", externalMessageId),
    new MessageAuthor.External(
        "myplugin:discord", externalUserId, displayName),
    content,
    Instant.now(),
    Duration.ofMinutes(5));

api.messages().publishExternal(request).thenAccept(result -> {
    if (result.status() == PublishStatus.ACCEPTED
            || result.status() == PublishStatus.DUPLICATE) {
        recordSuccess(result.messageId());
    } else if (result.retryable()) {
        scheduleBoundedRetry(result.diagnosticCode());
    } else {
        recordTerminalFailure(result.status(), result.diagnosticCode());
    }
});
```

`ExternalMessageIdentity(namespace, value)`がprovider単位の冪等性キーです。同じidentityを再投稿すると再描画せず、最初の論理UUIDを伴う`DUPLICATE`になります。identityと`MessageAuthor.External`のnamespaceは一致させてください。

返却される`CompletionStage`をPaper main threadやVelocity event threadで同期waitしてはいけません。非同期callbackを使い、`retryable()`がtrueの結果だけを回数・容量・期限が有限なqueueで再試行してください。

### 8.9 publish結果

| status | 意味 | 基本対応 |
| --- | --- | --- |
| `ACCEPTED` | Paperが最終内容を採用 | 成功 |
| `DUPLICATE` | 同一identityを処理済み | 成功として扱う |
| `CHANNEL_NOT_FOUND` | ChannelIdが存在しない | mappingを修正 |
| `FORBIDDEN` | 外部投稿が無効 | `accepts_external_messages`を確認 |
| `INVALID` | requestまたは確定identityが不正 | requestを修正し、同じまま再試行しない |
| `OVER_CAPACITY` | bounded queue/receiptが満杯 | `retryable()`に従いbackoff |
| `UNAVAILABLE` | authorityや配送先を利用不可 | `retryable()`に従いbackoff |
| `EXPIRED` | requestの有効期限切れ | 新しい利用者操作なしに再生成しない |

`ACCEPTED`は論理的な採用を表し、すべてのMinecraft clientでの描画完了を意味しません。

### 8.10 network状態

`api.networkStatus().current()`は次の状態を返します。

| state | 意味 |
| --- | --- |
| `READY` | 利用可能 |
| `DEGRADED` | 一部機能または配送先が劣化 |
| `UNAVAILABLE` | 現在利用不可 |
| `RELOADING` | reload中 |
| `SHUTTING_DOWN` | shutdown中 |

状態だけでなく`diagnosticCode`と`observedAt`も記録すると診断しやすくなります。

### 8.11 API入力制約

- content: 最大8,192 UTF-16 code units、UTF-8で最大32,767 bytes
- external request lifetime: 1秒以上24時間以下
- accepted message lifetime: 0秒より長く24時間以下
- namespace: 小文字英数字で開始し、小文字英数字・`.`・`_`・`:`・`-`を使用、最大64文字
- `ChannelId`: canonical UUID文字列
- channel page limit: 1から100

不正値はconstructorで`IllegalArgumentException`になります。

## 9. 配送・セキュリティ特性

### LunaChat側

- AES-256-GCMでwire frameを認証・暗号化
- session UUID、startup epoch、sequence、frame UUID、論理UUID、type、timestamp、payloadを認証
- 毎回random 96-bit nonceを使用
- logical message IDによるACK・再送・重複排除
- tamper、passphrase不一致、protocol不一致、stale sessionをfail closed
- replay window、receipt、render stage、backend outboxはすべてbounded

### LunaBridge側

- token-file優先
- 設定済みDiscord channelだけをingress allowlistとして扱う
- user-controlled mentionを抑制
- Discordの2,000文字制限へUnicode surrogateを壊さず収める
- outbound、observer receipt、publish retryをboundedにする
- Discord-originをMinecraft-originとして再転送しない

process crash直前・直後をまたぐDiscord送信について、durable exactly-onceは保証しません。LunaBridgeのobserver receiptは意図的にmemory内かつboundedです。

## 10. 障害診断

最初に次を実行します。

```text
lunabridge doctor
```

| 症状 | 主な確認箇所 |
| --- | --- |
| Discordへ送れない | token-file、Bot権限、gateway READY、mapping、Discord API障害 |
| DiscordからMinecraftへ届かない | mapping、`accepts_external_messages`、network state、publish結果 |
| `!p`が反応しない | 対応付け済みchannelか、text command有効か、trigger文字列、Message Content Intent |
| `Unknown ChannelId` | LunaChat channel migration/state同期と保存済みmapping |
| `backend identity mismatch` | 旧wire/旧JAR混在を解消し、全LunaChatをwire 2へ更新 |
| passphrase/authentication error | Velocityと全Paperの`sharePass`一致、12文字以上、全ノード再起動 |
| 空のbackendへ届かない | plugin message carrierとなるプレイヤー接続を確認 |
| Japanize表記がおかしい | LunaChatの`japanizeLine1Format`とLunaBridgeの`minecraft-chat-format`を確認 |

LunaBridgeやDiscordが停止しても、LunaChatのlocal Minecraft chatは独立して継続します。Velocity transportが停止してもPaper local renderingは継続しますが、network横断配送とintegration成功は報告されません。

## 11. 更新、rollback、停止

更新前に次をbackupします。

- 各Paperの`plugins/LunaChat`
- Velocityの`plugins/lunachat`
- LunaBridgeのplugin data directory

wire互換更新ではVelocity、Paper、LunaBridgeの順に更新します。wire非互換更新では全ノードを停止し、全LunaChat JARを揃えてからVelocity、Paperの順に起動します。

停止時、LunaBridgeは先にAPI subscriptionを閉じ、その後Discord connectorを閉じます。Velocity shutdown通知のoutbound drainは最大3秒です。LunaBridgeはLunaChat自体を停止・変更しません。

rollback時は停止した状態でbackupと対応するJARを戻します。将来schemaを古いbinaryで黙って書き換えてはいけません。

## 12. 配布JAR早見表

| JAR | 配置先 | 用途 |
| --- | --- | --- |
| `LunaChat.jar` | Paper | Minecraft chat本体、standalone authorityまたはnetwork edge |
| `LunaChat-Velocity.jar` | Velocity | network authority、API provider |
| `lunabridge-paper-standalone-0.3.0-beta.20.jar` | standalone Paperのみ | Discord connector |
| `lunabridge-velocity-0.3.0-beta.20.jar` | Velocityのみ | network構成のDiscord connector |

迷った場合は、単体PaperならPaper用2本、Velocity networkならVelocity用2本と各Paper用`LunaChat.jar`だけを配置してください。
