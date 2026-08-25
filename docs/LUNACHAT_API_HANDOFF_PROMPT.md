# LunaChat fork API redesign — Codex handoff prompt

以下を、LunaChat forkの新規API設計・実装・保守を担当する別のCodexへ、そのまま渡すプロンプトとして使用する。

---

## あなたの役割

LunaChat forkの公開API、Paper実装、Velocity実装、ネットワーク配送層を設計・実装し、継続保守できる状態にしてください。既存LunaChatのチャンネルチャット、日本語変換、書式、権限、mute/ban/hide、`/ch`、`/tell`、`/msg`、`/r`の意味論をLunaChat側の責務として維持します。

LunaBridgeは今後、Minecraft間配送を所有しません。LunaBridgeはDiscordとMinecraftの接続だけを担当し、LunaChatが公開するAPIを利用します。

対象プラットフォームは次のとおりです。

- Paper/Minecraft API `26.2`
- Velocity API `4.1.x`
- Java 25
- 単体Paper構成
- Velocityと複数Paperからなるネットワーク構成

作業開始時に対象リポジトリの`AGENTS.md`、バージョン規約、ライセンス、既存公開API、永続データ形式をすべて確認してください。コードや配布物を変更する場合は、そのリポジトリの規約に従って必ずバージョンを更新してください。

## 実現する最終構成

```text
単体Paper
---------
Discord <-> LunaBridge-Paper-Standalone <-> LunaChat Integration API <-> LunaChat-Paper

Velocityネットワーク
--------------------
Discord <-> LunaBridge-Velocity <-> LunaChat Integration API <-> LunaChat-Velocity
                                                              <-> LunaChat-Paper (backend A)
                                                              <-> LunaChat-Paper (backend B)
```

Velocity構成ではLunaBridgeを各Paperへ導入しません。各PaperにはLunaChat forkだけを導入します。LunaChat-PaperとLunaChat-Velocityの間の通信、認証、再送、重複排除、ルーティングはすべてLunaChat側が所有します。

Paper単体構成ではLunaBridge-Paper-Standaloneが同一Paper上のLunaChat公開APIを利用します。Paper単体版とVelocity版のLunaBridgeが同じネットワークで同時に動作する構成はサポートしません。

## 必須の設計原則

次の原則は変更しないでください。

1. VelocityまたはDiscordが停止しても、各PaperのローカルLunaChatメッセージを止めない。
2. 認証失敗、改ざん、不正なプロトコル状態はfail closedとする。
3. outbox、retry、dedup、replay window、handshake、イベント配送、外部投稿はすべて上限付きとする。
4. logical message identityとsecure frame identityを分離する。
5. ACK消失時の再送でプレイヤーへ同じメッセージを二重表示しない。
6. retryの暗号フレームは新しいframe identityとnonceで再暗号化する。
7. Discord起源をMinecraft起源に書き換えず、Discord→Minecraft→Discordループを構造的に防ぐ。
8. 表示名をプレイヤーIDとして使用しない。MinecraftプレイヤーはUUIDで識別する。
9. 変更可能なチャンネル名をネットワーク上の主識別子にしない。
10. 公開APIへBukkit、Velocity、JDA、内部暗号クラスを漏らさない。

## モジュール境界

最低でも次の論理モジュールへ分離してください。実際のartifact名は既存ビルドとの整合を取って決定し、最終設計書へ明記してください。

- `lunachat-api`: プラットフォーム非依存の公開契約だけを含む。
- `lunachat-core`: チャンネル意味論、メッセージモデル、配送状態機械などの内部実装。
- `lunachat-paper`: Paperイベント、ローカル表示、コマンド、Paper側network edge。
- `lunachat-velocity`: network authority、プレイヤー在席、サーバールーティング、Paper間配送。
- `lunachat-api-testkit`: API実装が共通契約を満たすことを検証するTCK。

`lunachat-api`はLunaChat本体と独立してコンパイル可能にし、実装詳細やプラットフォーム依存を含めないでください。公開型へ可変コレクションを返さず、入力値を構築時に検証してください。

## 公開APIの必須形

名前は既存パッケージ規約に合わせて調整できますが、意味論は次の契約を満たしてください。

```java
public interface LunaChatIntegrationApi {
    ApiVersion apiVersion();
    RuntimeRole runtimeRole();
    Set<Capability> capabilities();
    ChannelQueryService channels();
    MessageGateway messages();
    NetworkStatusService networkStatus();
}

public enum RuntimeRole {
    STANDALONE_AUTHORITY,
    NETWORK_AUTHORITY,
    NETWORK_EDGE
}

public enum Capability {
    QUERY_CHANNELS,
    OBSERVE_ACCEPTED_MESSAGES,
    PUBLISH_EXTERNAL_MESSAGES,
    QUERY_NETWORK_STATUS
}

public interface MessageGateway {
    Subscription observeAcceptedMessages(AcceptedMessageListener listener);
    CompletionStage<ExternalPublishResult> publishExternal(ExternalMessageRequest request);
}

@FunctionalInterface
public interface AcceptedMessageListener {
    void onAccepted(AcceptedMessage message);
}

public interface Subscription extends AutoCloseable {
    @Override void close();
}
```

LunaBridgeが起動できるのは、API実装が`OBSERVE_ACCEPTED_MESSAGES`と`PUBLISH_EXTERNAL_MESSAGES`を提供するauthority roleの場合だけです。

- 単体Paperでは`STANDALONE_AUTHORITY`が両機能を提供する。
- Velocityでは`NETWORK_AUTHORITY`が両機能を提供する。
- Velocity配下のPaperは`NETWORK_EDGE`であり、Discord bridge向けauthorityとして振る舞わない。

未対応操作を暗黙にローカル処理へフォールバックさせないでください。capability不足またはauthority不在を明示的な結果として返してください。

## メッセージモデル

公開モデルは少なくとも次の情報を持つ、不変かつプラットフォーム非依存の値オブジェクトにしてください。

```java
public record AcceptedMessage(
        UUID messageId,
        ChannelId channelId,
        String channelName,
        MessageOrigin origin,
        MessageAuthor author,
        String sourceServerId,
        String content,
        Instant createdAt,
        Instant expiresAt
) {}

public record ChannelId(String value) {}

public record MessageOrigin(
        OriginKind kind,
        String namespace,
        String sourceMessageId
) {}

public enum OriginKind {
    MINECRAFT,
    EXTERNAL,
    SYSTEM
}

public sealed interface MessageAuthor {
    record Player(UUID uuid, String accountName, String displayName) implements MessageAuthor {}
    record External(String namespace, String stableId, String displayName) implements MessageAuthor {}
    record System(String name) implements MessageAuthor {}
}
```

この形は例であり、Javaの実装可能性や既存互換性に応じて調整できます。ただし次の意味は変えないでください。

- `messageId`は一つの論理配送を表し、retry中は不変。
- `ChannelId`は永続的で、チャンネルrename後も変わらない。
- `channelName`は表示・診断用であり、設定や配送の主キーにしない。
- Minecraft authorは必ずUUIDを含む。
- 外部authorはprovider namespaceとprovider内stable IDを持つ。
- `content`はLunaChatが受理した最終本文。JDA型やAdventure Componentをwire/API契約に直接含めない。
- `origin.namespace`の例は`lunabridge:discord`。外部投稿がPaperで表示された後もoriginを維持する。
- 文字列長、UTF-8 byte長、時刻範囲、ID形式をAPI境界で検証する。

## `AcceptedMessage`イベントの正確な意味

イベント名だけで意味を推測させず、Javadocと契約テストで次を固定してください。

- LunaChatがフィルター、日本語変換、最終本文確定、チャンネル権限判定を完了した後に発行する。
- 「全クライアントが画面へ描画した」ことは意味しない。
- `messageId`についてauthority上で高々一回だけobserverへ通知する。
- ACK retryとauthority再起動が重なる場合も、少なくともmessage lifetimeとdedup graceの間は同じ`messageId`を再通知しない。必要なbounded durable receiptの範囲を設計書へ明記する。
- 単体Paperではstandalone authorityが通知する。
- Velocity構成ではVelocity network authorityだけが統合plugin向けに通知し、各Paper edgeから同じ統合イベントを重複発行しない。
- ACK消失やframe retryでは再通知しない。
- observerの例外や遅延でLunaChat配送を失敗させない。
- observerはノンブロッキングでなければならず、実装側もbounded dispatchと例外分離を行う。
- `EXTERNAL/lunabridge:discord`起源も必要なら観測可能だが、LunaBridgeは`MINECRAFT`だけをDiscordへ転送する。

「変換前」「ルーティング前」「ローカル配送完了」など別段階が必要なら、別名のイベントとして追加してください。一つのイベントへ複数の意味を持たせないでください。

## 外部投稿API

Discordなどの外部システムからの投稿は、LunaChat内部への直接再注入や偽プレイヤー生成ではなく、明示的なrequest APIで受け付けてください。

```java
public record ExternalMessageRequest(
        ChannelId channelId,
        ExternalMessageIdentity identity,
        MessageAuthor.External author,
        String content,
        Instant createdAt,
        Duration requestedLifetime
) {}

public record ExternalMessageIdentity(String namespace, String value) {}

public record ExternalPublishResult(
        PublishStatus status,
        UUID messageId,
        boolean retryable,
        String diagnosticCode
) {}

public enum PublishStatus {
    ACCEPTED,
    DUPLICATE,
    CHANNEL_NOT_FOUND,
    FORBIDDEN,
    INVALID,
    OVER_CAPACITY,
    UNAVAILABLE,
    EXPIRED
}
```

必須の契約は次のとおりです。

- Discord message IDなどのprovider側IDを`ExternalMessageIdentity`として渡し、同じ外部IDの再送を冪等にする。
- `DUPLICATE`では最初に割り当てた同じlogical `messageId`を返し、再表示しない。
- `ACCEPTED`はbounded delivery pipelineへcommitされたことを意味し、全クライアント表示完了を意味しない。
- channel、origin、author、本文、期限をLunaChat側でも再検証する。
- authority不在時にローカル成功へ偽装しない。
- requestの受理待ちでPaperメインスレッドまたはVelocityイベントスレッドをブロックしない。
- 無制限のoffline queueを作らない。

## チャンネルAPI

LunaBridgeが名前ではなく永続IDへ設定を結び付けられるようにしてください。

```java
public interface ChannelQueryService {
    Optional<ChannelDescriptor> find(ChannelId id);
    Optional<ChannelDescriptor> findByNameOrAlias(String value);
    ChannelPage listVisibleToIntegration(ChannelPageRequest request);
}

public record ChannelDescriptor(
        ChannelId id,
        String name,
        Set<String> aliases,
        boolean acceptsExternalMessages
) {}
```

- 既存チャンネルデータへ永続`ChannelId`を付与するmigrationを実装する。
- 一覧APIは件数上限とopaque cursorを持つpaginationにし、全チャンネルを無制限にメモリへ展開しない。
- migration前バックアップ、冪等な再実行、将来schemaの拒否を行う。
- renameしてもDiscord mappingが壊れない。
- 外部投稿可能かどうかをLunaChat側のポリシーとして表現する。
- integration用queryでパスワード、内部権限データ、非公開メンバー情報を不用意に公開しない。

## APIの発見とライフサイクル

プラットフォーム固有の発見方法を用意しつつ、取得後に利用する型は同じ`lunachat-api`契約にしてください。

- Paper: Bukkit ServicesManagerへ`LunaChatIntegrationApi`を登録し、disable時に解除する。
- Velocity: 安定したplugin IDを設定し、依存pluginが公開する`LunaChatApiProvider`または同等の明示的providerから取得する。LunaBridge側はVelocityの必須plugin dependencyを宣言する。
- API artifactを利用側と提供側で別々にshadeしてclass identityを壊さない。利用側は原則`compileOnly`とする。
- Velocityではconstructor中にruntime操作せず、`ProxyInitializeEvent`後にAPIを公開する。
- API ready前、shutdown中、reload中の結果を明示する。
- subscriptionはLunaBridge disable時に必ず解除できる。
- LunaChatのreloadで古いAPI instanceやlistenerをリークさせない。

## LunaChatが所有するネットワーク配送

LunaChat-PaperとLunaChat-Velocity間の配送を、一つの状態機械として設計してください。

- session lifecycle
- handshake
- logical message identity
- secure frame identity (`session + epoch + sequence`など)
- ACKとretry
- authenticated replay protection
- inbound idempotency/dedup
- bounded admission/backpressure
- heartbeatとVelocity再起動後の再接続
- carrier切替
- origin保持とloop prevention

正常なretryではlogical IDを維持し、secure frame IDとnonceを更新してください。認証済みの完全なframe replayは破棄しつつsessionを維持し、改ざんやsession不整合はfail closedとしてください。

Velocityのplugin messagingを使う場合は対象channelを常にhandledとしてclient-origin spoofingを防ぎ、backend sourceを検証してください。受信者がいるサーバーでは接続中playerをcarrierとして利用できます。無人backendへの即時状態配送まで必要とする場合は、player carrier依存を隠さず、認証済みdirect transportまたは永続authority stateを別途設計してください。

## チャンネル状態とコマンド

ネットワーク構成ではLunaChat-Velocityをネットワーク権威とし、チャンネルID、membership、ban/mute、招待、`/tell`、`/r`のサーバー間意味をLunaChat側で一貫させてください。

- 各Paperへ独立した矛盾する正本を作らない。
- durable authority stateとPaper側last-known-good cacheの境界を文書化する。
- Velocity停止中もローカルメッセージは継続する。
- 一貫性が必要なグローバル変更は、authority不在時に成功したように見せない。
- `/tell`と`/r`は表示名ではなくUUIDで相手とreply targetを管理する。
- offline messageを実装する場合は、暗号化、容量、期限、削除、監査を明示する。初期リリースで不要なら非対応を明記する。

## 障害分離

次の状態をテストし、ログと結果コードを区別してください。

- Discord/LunaBridge停止: LunaChatのPaper内・Paper間チャットは継続。
- LunaChat-Velocity停止: Paper内チャットは継続し、Paper間配送とDiscord連携は明示的にunavailable。
- 特定Paper停止: 他PaperとDiscordは継続。
- shared secret不一致、frame改ざん、未知protocol: fail closed。
- queue/dedup容量到達: boundedに拒否または期限切れさせ、ネットワーク全体を恒久停止させない。
- observer例外: LunaChat配送は継続。

## バージョンと互換性

製品バージョン、公開APIバージョン、wire protocol、config schema、永続データschemaを別々に管理してください。

- 公開APIはSemVerとし、binary compatibility testを追加する。
- wire互換を壊す変更はprotocol番号を更新し、混在versionを明示的に拒否する。
- 新しいLunaChat network protocolをLunaBridge wire protocol v2と同一物として扱わない。新規protocol番号体系を採用するか、由来と非互換性を明記する。
- config/永続schema migrationはbackup、冪等性、future-schema refusalをテストする。
- API artifactのバージョンとplugin metadataをビルドから生成し、手書きで重複させない。

## ライセンス上の必須確認

公式LunaChatはLGPLv3、現在のLunaBridgeはGPLv3です。LunaBridgeの配送コードをLunaChat forkへコピーする前に、全著作権者と再ライセンス権限を確認してください。

- 必要な権利がある場合: 移管するファイルの再ライセンス記録とprovenanceを残す。
- 権利がない場合: forkをGPLv3互換条件で配布するか、仕様とテストだけを参照して実装を作り直す。
- ヘッダーを消すだけ、履歴を隠す、由来を記録しないコピーは禁止。

法的判断を推測せず、採用した方針を`LICENSE`、`NOTICE`、設計書へ明記してください。

## 必須テスト

API TCKとprocess-level integration testを用意し、少なくとも次を再現してください。

### 共通API契約

- standalone authorityとnetwork authorityが同じpublish/observe契約を満たす。
- network edgeをauthorityとして誤利用できない。
- 同一external identityの再投稿は同じmessage IDを返し、表示とobserver通知は一回。
- channel rename後もstable `ChannelId` mappingが維持される。
- observer例外、遅延、解除、reloadでLunaChat本体が停止・リークしない。
- future API/config/schemaを黙ってdowngradeしない。

### 配送

- cold-start backendへのDiscord起源メッセージ。
- Velocity再起動後の自動再接続。
- ACKだけ消失した場合の再送と重複表示防止。
- frame再送とreplay protectionの整合。
- dedup容量到達後も期限切れ回収によりネットワークが再進行する。
- LunaChatの最終メッセージ境界。
- carrier切替。
- Discord→Minecraft→Discord loop防止。
- shared secret不一致と改ざん拒否。
- Velocity/transport停止中もローカルLunaChatが継続。
- `/tell`と`/r`のcross-backend UUID解決。

### 配置

- 単体Paper: LunaChat-Paper + LunaBridge-Paper-Standalone。
- Velocity: LunaChat-Velocity + LunaBridge-Velocity + 各PaperのLunaChat-Paper。
- Velocity構成でLunaBridge-Paper-Standaloneを誤導入した場合、二重botや二重relayを開始せず明示的に拒否する。

## 非目標

- LunaChatへJDAやDiscord token管理を組み込まない。
- LunaBridgeにチャンネルmembership、mute/ban、`/tell`、`/r`を実装しない。
- BridgeがLunaChatのprivate class、legacy mutable event、wire packetを直接参照する構成にしない。
- PaperごとにDiscord botを起動しない。
- 障害時に無制限queueを作らない。
- 名前一致による暗黙チャンネル統合をしない。

## 成果物

次をすべて提出してください。

1. API設計書と責務表。
2. `lunachat-api`とAPI Javadoc。
3. Paper standalone authority実装。
4. Velocity network authorityとPaper network edge実装。
5. channel ID/data migration。
6. API TCK、unit test、process integration test。
7. compatibility/versioning policy。
8. LunaBridge開発者向けintegration guideと最小コード例。
9. 障害モード、運用、upgrade/rollback手順。
10. ライセンスと移管コードのprovenance記録。

実装後は、各要件について「契約」「実装箇所」「テスト」「残存リスク」を表で報告してください。単体テストを通すだけで完了とせず、単体PaperとVelocity複数Paperの両構成を実プロセスで検証してください。

---

## 参考資料

- LunaChat公式リポジトリ: <https://github.com/ucchyocean/LunaChat>
- LunaChat公式Bungee pass-through設定: <https://github.com/ucchyocean/LunaChat/blob/master/src/main/resources/config.yml>
- Velocity plugin dependency: <https://docs.papermc.io/velocity/dev/dependency-management/>
- Velocity plugin messagingとsource検証: <https://docs.papermc.io/velocity/dev/plugin-messaging/>
