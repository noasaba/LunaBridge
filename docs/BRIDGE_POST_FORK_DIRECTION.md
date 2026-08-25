# LunaBridge post-fork direction and code recovery

## Decision

LunaBridgeは今後、DiscordとMinecraftのintegration connectorに限定する。LunaChat forkがMinecraftチャットの意味論とPaper間配送を所有し、LunaBridgeはLunaChatの公開Integration APIだけを利用する。

最終配置は次の二つに限定する。

| Runtime topology | LunaChat | LunaBridge |
| --- | --- | --- |
| 単体Paper | `LunaChat-Paper` | `LunaBridge-Paper-Standalone` |
| Velocity network | 各backendの`LunaChat-Paper` + proxyの`LunaChat-Velocity` | proxyの`LunaBridge-Velocity`だけ |

Velocity networkへ`LunaBridge-Paper-Standalone`を同時導入してはならない。設定上の注意だけにせず、runtime role/capabilityを検査して起動を拒否する。

## Target boundary

```text
                           LunaChat-owned boundary
             +----------------------------------------------+
Paper chat ->| LunaChat-Paper -> secure delivery -> Velocity|-> LunaChat-Paper
             |                         authority             |
             +-----------------------------+----------------+
                                           | public Integration API
                                           v
                                   LunaBridge-Velocity
                                           |
                                         Discord

Standalone Paper:
LunaChat-Paper <- public Integration API -> LunaBridge-Paper-Standalone -> Discord
```

LunaBridgeは次を知らない状態を完成形とする。

- LunaChat-PaperとVelocity間のplugin message channel。
- shared passphrase、handshake、session、epoch。
- ACK、retry、secure frame、replay window、dedup。
- remote LunaChat injectionの方法。
- LunaChatのlegacy mutable event。
- backend channel manifest。
- `/tell`、`/msg`、`/r`のルーティング。

LunaBridgeが知るのは、stable channel ID、accepted message、external publish result、runtime capabilityだけである。

## Proposed LunaBridge modules

LunaChat API完成後、LunaBridge `0.3.x`を次の構成へ移行する。

| Module | Purpose |
| --- | --- |
| `lunabridge-api-adapter`または`lunabridge-core` | LunaChat公開型からDiscord用内部型への小さな変換、設定検証、loop policy |
| `lunabridge-discord` | JDA gateway、mention安全化、bounded REST work、Discord ingress/egress |
| `lunabridge-paper-standalone` | Paper lifecycle、LunaChat service discovery、単体Paper通知・在席情報 |
| `lunabridge-velocity` | Velocity lifecycle、LunaChat provider discovery、network通知・全体在席情報 |
| `lunabridge-testkit` | fake LunaChat API providerと両runtime共通contract test |

`lunabridge-paper`という曖昧なartifact名は、既存利用者がVelocity backend用と誤認しやすい。`lunabridge-paper-standalone`へ明示的に変更する。旧artifactからのsilent replacementは行わない。

## Runtime behavior

### Velocity

`LunaBridge-Velocity`はLunaChat-Velocityへの必須plugin dependencyを宣言する。初期化時に次を検証する。

1. LunaChat API major versionが対応範囲内。
2. runtime roleが`NETWORK_AUTHORITY`。
3. `OBSERVE_ACCEPTED_MESSAGES`と`PUBLISH_EXTERNAL_MESSAGES` capabilityが存在する。
4. Discord mappingの全stable channel IDが解決可能で、外部投稿許可ポリシーと整合する。
5. 同じbot/applicationを所有するLunaBridge instanceが一つだけである。

条件を満たさない場合はDiscord botを開始せず、Minecraftチャットには影響を与えず、理由を一つの明確な診断として出す。

Minecraft→Discordは、LunaChat authorityのaccepted message observerを購読する。`origin.kind == MINECRAFT`だけを転送する。ACK retryやbackend再配送を直接観測しないため、Discordへの二重転送を避けられる。

LunaBridge側にも期限付き・上限付きのmessage ID receiptを置き、API再購読やauthority再接続による同一イベントを抑止する。ただしDiscord REST送信とローカルreceipt更新を一つのtransactionにはできないため、プロセス強制終了の瞬間まで含む完全なexactly-onceは保証しない。初期実装は「通常retryでは重複なし、crash windowではbest effort」と明記し、将来provider側idempotencyが利用可能になった場合だけ保証を強化する。

Discord→Minecraftは、Discord message snowflakeをexternal idempotency identityとしてLunaChatへ渡す。結果別の扱いは次のとおりとする。

| Publish result | LunaBridge action |
| --- | --- |
| `ACCEPTED` | 完了。必要なら短い監査ログを出す |
| `DUPLICATE` | 成功相当。再投稿しない |
| `OVER_CAPACITY` / retryable `UNAVAILABLE` | boundedで短時間だけretry。期限後は破棄 |
| `CHANNEL_NOT_FOUND` / `FORBIDDEN` / `INVALID` / `EXPIRED` | retryせず拒否ログ |

### Standalone Paper

`LunaBridge-Paper-Standalone`はBukkit ServicesManagerからLunaChat APIを取得し、次を検証する。

1. runtime roleが`STANDALONE_AUTHORITY`。
2. observe/publish capabilityが存在する。
3. serverがVelocity network edgeとして設定されていない。

Paper版だけがJDAを所有する。Velocity版と同じDiscord coreを利用するが、server-switch通知は提供しない。online player一覧、join、quit、startup、shutdown、first-loginなど、単体Paperで意味が明確な通知だけを実装する。

network edgeを検出した場合は、JDA接続前に起動を拒否する。LunaChatのローカル処理は継続させる。

## Configuration ownership

設定は責務に合わせて移す。

### LunaChatへ移す

- backend/server ID。
- Velocity有効化とtransport設定。
- shared passphraseまたは将来のbackend認証情報。
- network outbox、pending、dedup、replay、session上限。
- network protocol/config schema。
- channel stable ID、membership、moderation、PM network policy。

### LunaBridgeに残す

- Discord token。
- Discord channel ID ↔ LunaChat stable channel ID mapping。
- Discord ingress allowlist。
- text/slash command設定。
- startup、shutdown、join、quit、first-login、login、server-switch通知。
- first-login role mention。
- Discord REST上限とshutdown drain時間。

LunaBridge設定から`network.shared-pass`、`server.id`、`limits.network-outbox`、`limits.dedup-entries`を削除する。旧設定は直接上書きせずbackupを作り、Discord関連だけを新schemaへ移行する。LunaChat用secretをLunaBridge設定へ自動コピーしない。

mappingは現在のbridge key→LunaChat nameから、Discord channel ID→stable LunaChat `ChannelId`へ変更する。移行時に名前をAPIで一度解決し、複数候補や未解決はfail closedとして管理者に選択を求める。名前一致を永続契約にしない。

## Current code recovery map

現行`0.2.0`コードを一括削除せず、責務ごとに回収する。

### LunaChat forkへ移管する候補

| Current LunaBridge code | Destination/handling |
| --- | --- |
| `core/delivery/DeliveryStateMachine.java` | LunaChat network delivery core。契約テストと一緒に移管 |
| `core/delivery/DeliveryState.java` | LunaChat内部状態。公開APIへは露出しない |
| `core/crypto/*` | LunaChat transport security。license確認後に移管または再実装 |
| `core/protocol/*` | LunaChat wire protocolの参考/移管元。新しいprotocol namespaceとversionを定義 |
| `paper/PaperNetworkClient.java` | LunaChat-Paper network edgeへ統合。Bridgeからは削除 |
| `velocity/VelocityNetworkAuthority.java` | routing/delivery部分をLunaChat-Velocityへ統合。Discord依存を除去 |
| `paper/LunaChatAdapter.java` | fork内部の正式final-boundary実装へ置換。外部adapterとして残さない |
| `paper/RemoteInjectionScope.java` | origin-aware LunaChat内部配送へ置換。必要なら内部guardとして回収 |
| `velocity/EpochStore.java` | LunaChat transport epoch persistenceへ移管 |
| `core/model/BridgeMessage.java` | LunaChat APIの`AcceptedMessage`/内部network messageへ意味を移す |
| `core/model/BridgeOrigin.java` | LunaChat APIのorigin modelへ一般化 |
| `core/model/BridgeChannelMapping.java` | stable `ChannelId` migration後に廃止 |

### LunaBridgeへ残してrefactorする候補

| Current LunaBridge code | Future handling |
| --- | --- |
| `velocity/JdaDiscordGateway.java` | platform authorityへの直接依存を除き、LunaChat `MessageGateway`とpresence portへ接続 |
| `velocity/DiscordGateway.java` | `lunabridge-discord`へ移し、Paper/Velocity共通portにする |
| Discord mention neutralization、2,000文字境界 | 共通Discord safety utilityとして維持 |
| bounded JDA outbound、ready前queue、3秒shutdown drain | 共通Discord delivery componentとして維持 |
| `velocity/SeenPlayerStore.java` | 共通またはplatform adapterへ移し、standalone/Velocity双方で利用可能にする |
| join/quit/first-login/login/server-switch通知 | 共通notification formatter + platform event adapterへ分離 |
| `/players`と`!p` | presence service portを介して両runtimeで維持 |
| config migrationのbackup/future-schema refusal | Discord config schema用に維持し、network項目を除去 |

### 最終的にLunaBridgeから削除するもの

- `lunabridge:network` plugin message channel。
- handshake、session key、epoch、secure frame codec。
- Paper/Velocity間ACK、retry、heartbeat、dedup。
- backend manifestとLunaChat channel name整合検証。
- Paper側remote injectionとlegacy LunaChat event監視。
- network shared passphrase。
- Bridgeが所有するMinecraft間route。

## License and provenance gate

LunaBridge `0.2.0`はGPLv3、公式LunaChatはLGPLv3である。コード移管前に次を完了しない限り、LunaChat forkへファイルをコピーしない。

1. `git log --follow`、commit author、copyright headerで各候補の権利者を列挙。
2. 再ライセンス権限または全権利者の許諾を確認。
3. 移管表へsource commit、destination commit、license、変更概要を記録。
4. 選択したfork全体の配布licenseと依存関係を確認。

再ライセンスできない場合は次のいずれかを明示的に選ぶ。

- LunaChat fork/結合物をGPLv3互換条件で配布する。
- 現行コードをコピーせず、公開仕様とblack-box testを基にLunaChat側で再実装する。

履歴やlicense headerを消して「新規コード」と扱う方法は禁止する。

## Recovery sequence

### Phase 0 — Preserve the working release

- GitHub release/tag `v0.2.0`を変更しない。
- 現行二方向配送のテストを移管時のbehavioral baselineとして保存する。
- 現行artifactを削除せず、rollback可能にする。

### Phase 1 — Freeze contracts

- LunaChat Integration APIのSemVer契約を確定する。
- accepted event、external publish、stable channel ID、runtime role/capabilityをTCK化する。
- LunaBridge側へfake providerを追加し、実LunaChatなしでDiscord adapterをテストできるようにする。

### Phase 2 — Build LunaChat authority

- LunaChat-Paper standalone authorityを実装する。
- LunaChat-Velocity network authorityとLunaChat-Paper network edgeを実装する。
- 現行LunaBridge配送テストをLunaChat側へ移植する。
- `/tell`、`/r`、membership、moderationのcross-backend契約を追加する。

### Phase 3 — Introduce LunaBridge 0.3 prerelease

最初のbehavior/code変更時にLunaBridgeを`0.3.0-beta.1`へ更新する。API依存、artifact構成、config schema、plugin metadata、成果物名を同じ変更セットで更新する。

- `LunaBridge-Velocity`をLunaChat-Velocity API consumerへ変更。
- `LunaBridge-Paper-Standalone`を新設。
- JDA部分を共通化。
- 旧network configを新Discord-only schemaへ移行。
- 対応LunaChat API version rangeをmetadataと起動ログへ表示。

### Phase 4 — Dual-lane verification

同じDiscord connector contractを次の二構成で検証する。

1. standalone Paper + LunaChat-Paper + LunaBridge-Paper-Standalone。
2. Velocity + LunaChat-Velocity + 複数LunaChat-Paper + LunaBridge-Velocity。

旧LunaBridge `0.2.x` wireと新LunaChat transportを同時に有効化しない。migration test以外ではdual routingを禁止する。

### Phase 5 — Remove obsolete code

新LunaChat transportとLunaBridge両runtimeのprocess E2Eが通った後でだけ、Bridge内の旧Paper edgeとnetwork authorityを削除する。削除commitにもversion policyを適用する。

## Test ownership after split

### LunaChat repository

- session/handshake/ACK/retry/replay/dedup。
- backend routingとcarrier switch。
- final LunaChat message boundary。
- channel ID、membership、moderation、PM。
- local chat availability during proxy failure。
- public API TCK。

### LunaBridge repository

- JDA lifecycleとbounded REST work。
- Discord mention抑止と文字数境界。
- Discord channel allowlistとmapping validation。
- external identityの安定生成。
- bounded message receiptによるAPIイベント再通知の抑止と期限回収。
- LunaChat publish result別retry policy。
- origin filterによるloop prevention。
- standalone/Velocity role検出と誤配置拒否。
- notifications、`!p`、`/players`、first-login ledger。

### Cross-repository acceptance lane

- Minecraft A→BとDiscordへの一回だけの転送。
- Discord→全対象PaperとDiscordへの非反射。
- Velocity再起動、ACK消失、carrier切替。
- Discord停止時のMinecraft間継続。
- LunaBridge停止時のMinecraft間継続。
- LunaChat-Velocity停止時の各Paperローカル継続。
- standalone PaperでのDiscord往復。

## Compatibility and release policy

- 現行LunaBridge `0.2.0`は旧統合型architectureの最終安定版として保持する。
- 新architectureは`0.3.0-beta.1`から開始する。
- 新Bridge config schemaは旧network項目を保持しないためschema番号を更新する。
- LunaChat API major、LunaChat network protocol、LunaBridge product versionを混同しない。
- `0.2.x`とのwire互換を装わず、全node同時更新または明示的な停止移行を要求する。
- prereleaseごとにartifact metadata、JAR名、ドキュメント、checksumを一致させる。

## Release gates

次をすべて満たすまでstable releaseにしない。

- LunaChat API TCKがPaper standalone authorityとVelocity authorityの双方で成功。
- 全unit/integration/process E2E成功。
- 配置誤りがJDA接続前に拒否される。
- Discord tokenがPaper network edgeへ不要かつ配置されていない。
- Bridge停止がMinecraft間同期へ影響しないことを実プロセスで確認。
- LunaChat network停止が各Paperのローカルチャットを止めないことを確認。
- license/provenance記録完成。
- upgrade、rollback、旧config migrationを実環境で確認。

## Immediate next action

LunaBridge側の次のcode変更は、LunaChat APIの最初のversioned artifactとTCKが利用可能になってから開始する。それまでは`0.2.0`を保守し、推測したAPIへ先行して結合しない。

LunaChat担当Codexから最低限、次を受領する。

1. API artifactの座標とversion。
2. supported API version range。
3. Paper/Velocityでのprovider発見方法。
4. runtime role/capability契約。
5. accepted eventとexternal publishのJavadoc。
6. channel ID migration仕様。
7. fake providerまたはAPI testkit。
8. license/provenance方針。
