# Prism - AI Agent 指南

本文件面向 AI 编码代理，假设读者对本项目一无所知。所有内容均已与实际代码核对。

## 项目概览

**Prism**（原名 Json Helper）是一个面向 IntelliJ IDEA 的效率插件：JSON 侧提供数据编辑、查询、生成与多格式互转能力；增强侧集成代码地图（minimap）、彩虹括号与变量高亮、项目树压缩包浏览与文件注释、代码截图、项目 / HTTP / 端口搜索等编辑器增强功能。

**项目元数据：**

| 属性        | 值                                              |
|-------------|-------------------------------------------------|
| Group ID    | `com.acme`                                      |
| Artifact ID | `prism`                                   |
| 插件 ID     | `com.acme.prism`                          |
| 作者        | 拒绝者                                          |
| 当前版本    | `0.18.8`（定义在 `settings.gradle` 的版本表中） |
| 许可证      | MIT                                             |
| GitHub      | https://github.com/fc6a1b03/prism         |

**核心功能：**

- JSON 编辑、格式化、压缩、转义与反转义
- 自动修复损坏 JSON（单引号/缺逗号/尾逗号/注释/JSONP 包装/裸键/非标准字面量/NDJSON 流/MongoDB 扩展 JSON 包装），带置信度与修复日志
- 按键递归排序、扁平化/还原（点号键与嵌套结构互转）、JSON Schema（2020-12）生成
- 右侧面板深度工具：工具条（修复/排序/展开/还原/Schema/分析）、结构分析（键/对象/数组/深度/大小、重复键检测）、树形面板展开全部/折叠全部与选中节点路径/值详情
- 从 JSON 生成 Java Class / Record
- 从 Java 类字段复制 JSON 结构
- JsonPath / JMESPath 查询与树形浏览
- URL、JWT、本地文件路径、Web 路径自动解析为 JSON
- JSON 与 XML / YAML / TOML / Properties / CSV / XLSX / Base64 / URL Params / Markdown 互转
- Search Everywhere 集成：项目搜索、HTTP 请求文件搜索、端口搜索、压缩包内容搜索
- 项目树中将压缩包（zip / 7z / jar / war / ear / tar / tar.gz / tgz / tar.bz2 / tbz2 / tar.xz / txz / gz / bz2 /
  xz）作为目录展开浏览
- 项目树文件信息：文件名右侧显示头部注释摘要与修改时间
- 编辑器增强：彩虹括号配对高亮、彩虹变量高亮、颜色字面量 Gutter 色块、代码地图（minimap）
- 代码截图复制

## 技术栈

- **语言**: Java 25（toolchain 与 `options.release` 均取自版本表 `jvm`）
- **构建工具**: Gradle 9.7.0 + IntelliJ Platform Gradle Plugin 2.18.1
- **目标平台**: IntelliJ IDEA 2026.2（sinceBuild = 262，由 IGP 2.14+ 按目标平台 major build 默认生成，无需显式声明）
- **UI 框架**: IntelliJ Platform UI（Swing-based）
- **字符编码**: 全项目 UTF-8

### 依赖版本

版本统一在 `settings.gradle` 的 `gradle.ext.versions` 表中维护：

| 依赖                    | 版本   | 用途                     |
|-------------------------|--------|--------------------------|
| IntelliJ Platform       | 2026.2 | IDE 集成                 |
| Hutool                  | 5.8.47 | 工具库（core + http）    |
| Jackson                 | 3.2.1  | JSON/数据格式处理（BOM） |
| Fastjson2               | 2.0.64 | JSON 解析与校验          |
| Auth0 JWT               | 4.5.2  | JWT Token 解析           |
| Apache POI              | 5.5.1  | Excel 文件支持           |
| Apache Commons Compress | 1.28.0 | 压缩包格式支持           |
| JUnit                   | 6.1.2  | 单元测试（BOM，test 域） |
| Apache Commons Lang3    | 3.20.0 | 通用工具（强制统一版本） |

### 捆绑插件依赖（`build.gradle` 与 `plugin.xml` 中声明）

- `org.toml.lang` - TOML 语言支持
- `com.intellij.java` - Java 语言支持
- `com.intellij.gradle` - Gradle 支持
- `com.intellij.properties` - Properties 文件支持
- `com.intellij.modules.json` - JSON 模块
- `com.intellij.modules.vcs` - VCS 模块（Git 行状态）
- `org.jetbrains.plugins.yaml` - YAML 支持

## 项目结构

```
src/main/
├── java/com/acme/json/helper/
│   ├── common/                    # 通用工具类和枚举
│   │   ├── enums/
│   │   │   ├── AnyFile.java       # 支持的文件类型枚举 (JSON, XML, YAML, TOML, CLASS, RECORD, CSV, XLSX 等)
│   │   │   └── SupportedLanguages.java  # 支持的语言枚举
│   │   ├── ActionEventCheck.java  # 动作事件检查（sealed interface Check）
│   │   ├── Clipboard.java         # 剪贴板操作
│   │   ├── CollectionTypeHandler.java   # 集合类型处理
│   │   ├── TemporalTypeHandler.java     # 时间类型处理
│   │   └── UastSupported.java     # UAST (Universal AST) 支持
│   ├── core/                      # 核心功能模块
│   │   ├── archive/               # 压缩包格式识别、索引、树节点、搜索、打开器、图标
│   │   │   ├── ArchiveFormats.java      # 压缩包格式枚举与条目读取
│   │   │   ├── ArchiveIndex.java        # 压缩包索引
│   │   │   ├── ArchiveContentIndex.java # 压缩包内容索引（供搜索）
│   │   │   ├── ArchiveTreeProvider.java # 项目树压缩包节点提供
│   │   │   ├── ArchiveIconProvider.java # 压缩包图标提供
│   │   │   ├── ArchiveOpener.java       # 压缩包条目打开器
│   │   │   ├── ArchiveSearch.java       # 压缩包内容搜索
│   │   │   └── ArchiveEntryNode.java    # 压缩包条目树节点
│   │   ├── editor/                # 编辑器集成
│   │   │   ├── FileDropHandler.java         # 文件拖放处理
│   │   │   ├── JsonEditorPushProvider.java  # 编辑器推送提供者
│   │   │   └── record/EditorState.java      # 编辑器状态记录 (Base64 编码/解码)
│   │   ├── fileinfo/              # 项目树文件信息
│   │   │   ├── FileCommentExtractor.java    # 文件头部注释提取
│   │   │   ├── FileInfoNodeDecorator.java   # 项目树节点装饰
│   │   │   ├── FileInfoCacheService.java    # 文件信息缓存服务
│   │   │   └── FileInfoDisplay.java         # 文件信息展示
│   │   ├── json/                  # JSON 处理
│   │   │   ├── JsonOperation.java       # JSON 操作接口 (sealed interface)
│   │   │   ├── JsonAnalyzer.java        # JSON 结构分析（统计/重复键检测）
│   │   │   ├── JsonCompressor.java      # JSON 压缩
│   │   │   ├── JsonEscaper.java         # JSON 转义
│   │   │   ├── JsonFlattener.java       # JSON 扁平化（点号键展开）
│   │   │   ├── JsonFormatter.java       # JSON 格式化
│   │   │   ├── JsonRepairer.java        # JSON 修复（损坏 JSON 自动修复，带置信度）
│   │   │   ├── JsonSchemaGenerator.java # JSON Schema (2020-12) 生成
│   │   │   ├── JsonSearchEngine.java    # JSON 搜索引擎 (JsonPath/JMESPath)
│   │   │   ├── JsonSorter.java          # JSON 键递归排序
│   │   │   ├── JsonUnEscaper.java       # JSON 反转义
│   │   │   └── JsonUnflattener.java     # JSON 反扁平化（点号键还原嵌套）
│   │   ├── minimap/               # 代码地图（编辑器缩略图）
│   │   │   ├── MinimapEditorFactoryListener.java  # 编辑器挂载监听器
│   │   │   ├── MinimapPanel.java / MinimapView.java / MinimapRenderer.java
│   │   │   ├── MinimapGeometry.java / MinimapScrollState.java
│   │   │   └── CharacterWeights.java              # 字符油墨权重
│   │   ├── notice/
│   │   │   └── Notifier.java      # 线程安全通知系统
│   │   ├── parser/                # 解析逻辑
│   │   │   ├── AnyParser.java     # 自动格式检测 (TOML/YAML/Properties/CSV)
│   │   │   ├── ClassParser.java   # Java 类解析 (PSI-based)
│   │   │   ├── JsonNodeParser.java      # JSON 节点解析
│   │   │   ├── JsonParser.java    # JSON 转换分发器（EnumMap 注册表）
│   │   │   ├── JwtParser.java     # JWT Token 解析
│   │   │   ├── PathParser.java    # 本地/Web 路径解析
│   │   │   ├── TypeResolver.java  # Java 类型解析
│   │   │   └── converter/         # 格式转换器（实现 DataFormatConverter）
│   │   │       ├── DataFormatConverter.java  # 转换器接口
│   │   │       ├── XmlConverter.java / YamlConverter.java / TomlConverter.java
│   │   │       ├── CsvConverter.java / XlsxConverter.java
│   │   │       ├── PropertiesConverter.java / Base64Converter.java / UrlParamsConverter.java
│   │   │       ├── SqlConverter.java / MarkdownTableConverter.java
│   │   │       ├── ClassConverter.java / RecordConverter.java
│   │   │       └── JavaStructure.java / TableStructure.java  # 结构模型
│   │   ├── rainbow/               # 彩虹高亮
│   │   │   ├── RainbowBracketPairEditorFactoryListener.java # 括号配对高亮监听器
│   │   │   ├── RainbowBracketPairHighlighter.java           # 括号高亮器
│   │   │   ├── RainbowBracketPairColors.java                # 括号颜色
│   │   │   ├── RainbowBracketPairColorSettingsPage.java     # 括号颜色设置页
│   │   │   ├── RainbowVariableEditorFactoryListener.java    # 变量高亮监听器
│   │   │   ├── RainbowVariableHighlighter.java              # 变量高亮器
│   │   │   ├── ColorHighlighterEditorFactoryListener.java   # 颜色字面量色块监听器
│   │   │   └── ColorLiteralParser.java / ColorHighlightListener.java
│   │   ├── screenshot/
│   │   │   └── CodeScreenshotSupplier.java  # 代码截图
│   │   ├── search/                # 搜索功能
│   │   │   ├── cache/SearchCache.java     # 搜索缓存
│   │   │   ├── ProjectSearch.java         # 项目搜索
│   │   │   ├── HttpRequestSearch.java     # HTTP 请求文件搜索
│   │   │   ├── PortSearch.java            # 端口搜索
│   │   │   ├── ArchiveSearch.java         # 压缩包内容搜索
│   │   │   └── item/                      # 搜索项类型
│   │   │       ├── HttpRequestItem.java
│   │   │       ├── PortSearchItem.java
│   │   │       ├── ProjectNavigationItem.java
│   │   │       └── ArchiveEntryItem.java
│   │   └── settings/              # 插件设置
│   │       ├── PluginSettings.java            # 设置页面 (applicationConfigurable)
│   │       ├── PluginSettingsState.java       # 持久化状态 (applicationService)
│   │       └── ProjectDisposableService.java  # 项目级 Disposable
│   └── ui/                        # 用户界面
│       ├── MainToolWindowFactory.java       # 工具窗口工厂
│       ├── action/
│       │   ├── json/      # CopyJsonAction / JsonHelperAction / CreateClassFromJsonAction
│       │   ├── screenshot/ # CodeScreenshot（代码截图动作）
│       │   └── search/    # ProjectSearchAction / HttpRequestSearchAction
│       ├── dialog/        # ConvertAnyDialog / CreateClassDialog
│       ├── editor/        # Editor.java (sealed interface) / CustomizeEditorFactory.java
│       ├── panel/         # MainPanel（主面板）/ JsonTreePanel（JSON 树形面板）
│       └── search/        # ProjectSearchFactory / HttpRequestSearchFactory / PortSearchFactory / ArchiveSearchFactory
└── resources/
    ├── META-INF/
    │   ├── plugin.xml             # 插件配置（actions/extensions/依赖声明）
    │   └── pluginIcon.svg         # 插件图标
    ├── icons/
    │   └── pluginIcon.svg
    ├── colorSchemes/
    │   └── RainbowBracketPairDefault.xml  # 彩虹括号默认颜色方案
    └── messages/                  # 国际化资源
        ├── PrismBundle.properties       # 英文（默认）
        └── PrismBundle_zh_CN.properties # 中文
```

**注意**：`src/test` 为 JUnit 6 单元测试目录（覆盖纯逻辑单元：JSON 操作、格式转换器、检测引擎、编解码、压缩包索引、代码地图几何、文件注释提取、编辑器状态），
`./gradlew test` 运行。

## 构建命令

项目已生成 Gradle Wrapper（`gradlew` / `gradlew.bat`，固定 9.7.0），优先使用 `./gradlew`；也可使用系统 `gradle` 命令：

```bash
# 打印版本信息
./gradlew printAllVersions      # 项目/平台/Java/Gradle 版本一览
./gradlew printVersion
./gradlew printJavaVersion
./gradlew printGradleVersion
./gradlew printIdeaVersion

# 编译
./gradlew compileJava

# 运行单元测试
./gradlew test

# 运行带插件的沙箱 IDE（Darcula 主题，开发者模式）
./gradlew runIde

# 构建插件 ZIP
./gradlew buildPlugin

# 完整构建并校验（CI 同款）
./gradlew clean buildPlugin verifyPluginProjectConfiguration verifyPluginStructure
```

沙箱清理：使用 IGP 内置 `cleanSandbox` 任务（`prepareSandbox` 前自动执行，清理 `.intellijPlatform/sandbox`）。

### 输出产物

```
build/distributions/prism-x.x.x.zip
```

安装方式：IntelliJ IDEA 中 `Settings` -> `Plugins` -> 齿轮按钮 -> `Install Plugin from Disk...`

### `runIde` JVM 参数（`build.gradle` 中配置）

```
-Xms1g -Xmx4g -XX:+UseZGC
-Dfile.encoding=UTF-8 -Duser.timezone=GMT+8 -Didea.wsl.disable=true
--add-exports=jdk.compiler/com.sun.tools.javac.{api,tree,util,parser}=ALL-UNNAMED
```

### Gradle 优化配置（`gradle.properties`）

仅保留非默认值且可移植的项：`caching`、`parallel`、`useCacheRedirector=false`；JVM
`-Xms1g -Xmx5g -XX:MaxMetaspaceSize=1g -XX:+HeapDumpOnOutOfMemoryError`（不得再配 `-Djava.security.manager=allow`，JDK 24+
会直接拒绝启动）。 本机相关配置（`org.gradle.java.home`、`installations.paths`、代理）一律放用户级
`GRADLE_USER_HOME/gradle.properties`，不入库。

## 代码风格规范

### 语言和注释

- **主要文档语言**: 中文 (zh-CN)
- **代码注释 / Javadoc**: 中文
- **变量/方法名**: 英文 (camelCase)； **类名**: 英文 (PascalCase)
- **资源文件**: 双语（英文默认 + 中文）

### Java 编码约定（源码中实际使用的模式）

1. **Sealed Interfaces**：核心抽象用 Java sealed interface 约束实现集
   ```java
   public sealed interface JsonOperation permits
       JsonCompressor, JsonEscaper, JsonFlattener, JsonFormatter, JsonRepairer,
       JsonSchemaGenerator, JsonSearchEngine, JsonSorter, JsonUnEscaper, JsonUnflattener { }
   ```
   新增实现类时必须同步修改 `permits` 子句。

2. **模式匹配 switch**：
   ```java
   switch (someValue) {
       case final ActionEventCheck.Check.Failed failed -> failed.action().run();
       case final ActionEventCheck.Check.Success ignored -> { /* ... */ }
   }
   ```

3. **局部变量使用 `var`**； **方法参数标记 `final`**。

4. **使用 Hutool 的 `Opt`** 做 null-safe 链式操作。

5. **使用 `Boolean.TRUE` / `Boolean.FALSE`** 替代原始布尔字面量。

6. **Record 作为 DTO**，如 `EditorState(Integer editorId, String content)`、`ArchiveFormats.RawEntry`。

7. **Javadoc 格式**（带作者与日期）：
   ```java
   /**
    * 功能描述
    * @author 拒绝者
    * @date 2025-01-18
    */
   ```

### 性能要求

1. **避免内存泄漏**：try-with-resources 释放流/连接；不在静态集合中持有大对象。
2. **大数据量处理**：流式/增量解析，避免整文件载入内存（超过 1MB 的 JSON 尤其注意）。
3. **缓存策略**：复用 `SearchCache`、`FileInfoCacheService`、`ArchiveCacheService` 等现有机制；由 project/application
   service 托管生命周期；设置合理过期时间与大小限制。
4. **异步执行**：耗时操作用 `Task.Backgroundable` 放后台线程并显示进度，不阻塞 UI 线程。
5. **对象复用**：优先 `EnumMap` 而非线性查找（见 `JsonParser`）；循环内不创建临时对象；字符串拼接用 `StringBuilder`。
6. **延迟加载**：重型组件懒加载，大量数据分页展示。

### IntelliJ Platform 编码模式

1. **Actions**：继承 `AnAction`，重写 `actionPerformed` 与 `update`，并显式声明 `getActionUpdateThread()`（通常返回
   `ActionUpdateThread.BGT`）。
2. **Tool Windows**：实现 `ToolWindowFactory`（见 `MainToolWindowFactory`）。
3. **Settings**：`ApplicationConfigurable` + `ApplicationService`（见 `PluginSettings` / `PluginSettingsState`）。
4. **Notifications**：统一走 `Notifier` 工具类（线程安全，自动切换 EDT，静默处理异常）。
5. **线程安全**：UI 更新用 `ApplicationManager.getApplication().invokeLater()` 切到 EDT；PSI 读取使用官方读锁
   API；避免在锁上下文内执行可能触发写锁的操作（如 `ProjectUtil.openOrImport`）。

## 国际化 (i18n)

所有用户可见字符串必须外部化，禁止硬编码：

- 默认（英文）: `src/main/resources/messages/PrismBundle.properties`
- 中文: `src/main/resources/messages/PrismBundle_zh_CN.properties`

访问模式：

```java
private static final ResourceBundle BUNDLE =
        ResourceBundle.getBundle("messages.PrismBundle");
String text = BUNDLE.getString("key.name");
```

新增字符串时两个文件必须同步添加。

## 测试策略

纯逻辑单元（JSON 操作、格式转换器、检测引擎、编解码、压缩包索引、代码地图几何、文件注释提取、编辑器状态）由 `src/test` 下的
**JUnit 6** 测试覆盖，`./gradlew test` 运行；依赖 IDE 平台运行环境的部分（PSI、编辑器、Search
Everywhere、项目树节点、代码地图渲染）不做单元测试，验证方式为手动测试：

1. `./gradlew runIde` 启动带插件的沙箱 IDE
2. 测试编辑器右键菜单中的所有动作（复制 JSON、推送面板、代码截图等）
3. 验证 "Prism" 工具窗口功能（编辑、格式化、查询、树形浏览、格式转换）
4. 检查各种格式转换（XML/YAML/TOML/CSV/XLSX 等）
5. 验证项目树中压缩包节点展开、文件注释与修改时间显示
6. 验证代码地图、彩虹括号、彩虹变量、颜色字面量色块等功能
7. 验证 Search Everywhere 中的项目搜索、HTTP 搜索、端口搜索、压缩包内容搜索
8. 提交前至少跑通 `./gradlew clean buildPlugin verifyPluginProjectConfiguration verifyPluginStructure`

## 部署 / CI-CD

GitHub Actions 工作流 `.github/workflows/build_jar.yml`：

- **触发方式**: 手动 (`workflow_dispatch`)
- **运行环境**: `ubuntu-latest`，Java 25，Gradle 9.7.0
- **流程**: 从 `settings.gradle` 提取版本号 → 缓存 IntelliJ Platform 构件 → `test` + `buildPlugin` +
  `verifyPluginStructure` → 上传 ZIP 工件 → 创建 GitHub Release

发布新版本时：修改 `settings.gradle` 中 `gradle.ext.versions.ver`，并同步更新 `build.gradle` 中的 `changeNotes`。

**提交信息规范**：Conventional Commits 中文标题 + 版本发布注明（如 `build: 升级 fastjson2 至 2.0.64，发布 0.19.7`），body 逐条列改动点。

**GitHub mention 陷阱**：提交信息中禁止裸写 `@xxx`（如 `@type`）——GitHub 会识别为 @mention 并通知同名用户，且引号/反引号包裹**不豁免**。替代写法：全角 `＠type`、`at-type`，或直接中文描述（"type 字段"）。一旦推送/合并进共享历史即无法补救（禁止 force push 改写）。

## 插件配置（`META-INF/plugin.xml` 关键点）

- **Actions**（均注册在编辑器右键/相关菜单组）：
    - `CodeScreenshot` - 代码截图（快捷键 Shift+Ctrl+S，`EditorPopupMenu`）
    - `CopyJsonAction` - 复制 JSON（`EditorPopupMenu`）
    - `JsonHelperAction` - 推送 JSON 到工具窗口（`EditorPopupMenu` + `ConsoleEditorPopupMenu`）
    - `CreateClassFromJsonAction` - 从 JSON 创建类（`NewGroup` + `ProjectViewPopupMenu`）
    - `ProjectSearchAction` / `HttpRequestSearch` - 搜索动作（`GoToTargetEx`）
- **Tool Window**: id 为 "Prism"，锚定右侧 (`anchor="right"`, `secondary="true"`, `canCloseContents="true"`)
- **Settings**: Tools 菜单下的 `applicationConfigurable`
- **Search Everywhere Contributors**: `ProjectSearchFactory`、`HttpRequestSearchFactory`、`PortSearchFactory`、
  `ArchiveSearchFactory`
- **项目树扩展**：`ArchiveTreeProvider`（压缩包节点）、`ArchiveIconProvider`（压缩包图标）、`FileInfoNodeDecorator`（文件信息装饰）
- **编辑器工厂监听器**：`RainbowBracketPairEditorFactoryListener`、`RainbowVariableEditorFactoryListener`、
  `ColorHighlighterEditorFactoryListener`、`MinimapEditorFactoryListener`
- **颜色方案**：`RainbowBracketPairDefault.xml`，并注册 `RainbowBracketPairColorSettingsPage`
- **通知组**: `JSONGenerator.NotificationGroup`（BALLOON，走 i18n bundle）

## 架构模式

### 转换器注册表模式

`JsonParser` 用 `EnumMap` 做格式转换分发，启动时一次性注册并 `Map.copyOf` 固化：

```java
private static Map<AnyFile, DataFormatConverter> createConverters() {
    final EnumMap<AnyFile, DataFormatConverter> converters = new EnumMap<>(AnyFile.class);
    register(converters, AnyFile.XML, new XmlConverter());
    // ... 共 12 个转换器
    return Map.copyOf(converters);
}
```

未注册的格式抛出 `IllegalArgumentException("不支持的格式")`。

### 动作事件检查

`ActionEventCheck` 用 sealed interface 做类型安全的前置检查：

```java
public sealed interface Check permits Check.Failed, Check.Success {
    record Success() implements Check {
    }

    record Failed(Runnable action) implements Check {
    }
}
```

### 编辑器状态管理

`EditorState` record 支持 List 级别的 Base64 编码/解码，用于编辑器内容持久化。

### 压缩包处理

`ArchiveFormats` 枚举集中维护所有支持的压缩格式及其条目读取策略：

- zip 系（zip / jar / war / ear）：随机读取，构建索引时顺带解压头部提取注释
- 7z：顺序流，单次遍历提取条目与注释
- tar 系（tar / tar.gz / tgz / tar.bz2 / tbz2 / tar.xz / txz）：流式单次遍历
- 单文件压缩（gz / bz2 / xz）：去后缀后作为单个条目处理

条目打开通过 `ArchiveOpener` 统一路由到 `JarFileSystem` 或临时解压；内容搜索通过 `ArchiveContentIndex` 建立文本条目内容索引。

### 代码地图

`MinimapEditorFactoryListener` 在编辑器创建时于右侧挂载 `MinimapPanel`，释放时移除并 dispose；面板使用自研字符权重纹理渲染，非
EDT 线程读取文档 + 150ms 防抖，大文件自动跳过，支持点击/拖动/Shift 扩选导航、滚轮滚动、左缘拖拽调宽（40~200 像素持久化）。

## 常见任务指南

### 添加新的 JSON 操作

1. 在 `core/json/` 创建类实现 `JsonOperation`
2. 在 `JsonOperation.java` 的 `permits` 子句中登记
3. 在 `MainPanel.java` 的 `createToolPanel()` 工具条或右键菜单中接入 UI（纯逻辑操作走 `optJson` 通用链路；需要元数据反馈的走自定义 action，如 `repairJson` / `unflattenJson`）
4. 两个 properties 文件同步添加 i18n 键

### 添加新的 JSON 分析能力

1. 纯逻辑统计放 `core/json/JsonAnalyzer.java`（如 `Stats` / `duplicateKeys`），JUnit 单测覆盖
2. 弹窗展示放 `ui/dialog/JsonAnalyzeDialog.java`（参考 `ConvertAnyDialog` 的 DialogWrapper 模式）
3. 树面板统计摘要/按钮在 `ui/panel/JsonTreePanel.java` 的 `createStatsBar()` / `createTreeActions()` 接入

### 添加新的格式转换器

1. 在 `core/parser/converter/` 创建转换器类，实现 `DataFormatConverter`
2. 在 `JsonParser.java` 的 `createConverters()` 中注册
3. 如需新文件类型，在 `AnyFile.java` 枚举中添加
4. 如需新语言支持，更新 `SupportedLanguages.java`

### 添加新的 Action

1. 在 `ui/action/` 下对应子包创建动作类，继承 `AnAction`
2. 重写 `getActionUpdateThread()` / `update()` / `actionPerformed()`
3. 在 `META-INF/plugin.xml` 注册 `<action>` 并指定菜单组
4. 添加 i18n 字符串

### 添加新的 Search Everywhere 贡献者

1. 创建工厂类实现 `SearchEverywhereContributorFactory`
2. 创建搜索类继承 `SearchEverywhereContributor`
3. 在 `plugin.xml` 注册 `<searchEverywhereContributor>` 扩展

### 添加新的编辑器增强功能

1. 创建 `EditorFactoryListener` 实现类，按需监听编辑器创建/释放事件
2. 通过 `ProjectDisposableService` 管理生命周期，避免泄漏
3. 在 `PluginSettingsState` 中添加开关字段，在 `PluginSettings` 中添加 UI 组件
4. 在 `plugin.xml` 中注册 `<editorFactoryListener>` 扩展
5. 同步更新 i18n 资源

## 安全注意事项

1. **本地数据处理**: 所有 JSON、Java 代码处理均在本地完成，无外部数据传输
2. **HTTP 请求搜索**: 仅扫描本地项目文件，不发起真实请求
3. **剪贴板操作**: 需用户显式触发
4. **文件操作**: 处理前校验 JSON 格式
5. **压缩包解压**: 对单文件压缩设置压缩率兜底防护，防止解压炸弹；大文件/条目大小限制在打开器与搜索索引中均有护栏
6. **Fastjson2 AutoType 安全**: 2026-07-27 披露 fastjson2 ≤ 2.0.62 的 FNV-1a 哈希碰撞可绕过 AutoType 校验（XVE-2026-42782，CVSS 9.8），已在 **2.0.63** 修复（白名单 hash 命中后文本回验、URL 特殊字符类型名拒绝、accept 前缀不再覆盖 ClassLoader/DataSource/RowSet 危险基类）。
   - **当前版本**: 已升级至 2.0.64（2.0.63 安全修复 + 2.0.64 JSONB OOM/DoS、Metaspace 泄漏、record 泛型等修复）
   - **默认安全**: fastjson2 默认关闭 AutoType，本插件的所有 JSON 解析（`JSON.parse()` / `JSON.parseObject()` 不传 `SupportAutoType`）不受影响
   - **纵深防御**: Gradle 的 `runIde` 和 `test` 任务均配置 JVM 参数 `-Dfastjson2.parser.safeMode=true`；实测 2.0.62/2.0.64 下 SafeMode 开启时显式传入 `SupportAutoType` 同样不实例化 @type（结果恒为 `JSONObject`）
   - **已知限制**: SafeMode 仅有 JVM 参数与编程式 `JSON.configSafeMode()` 两种方式，无法阻止在 AutoType 白名单场景下的合法放行；XVE-2026-42782 影响的是 AutoType 白名单场景，本插件从不开启 AutoType，攻击面极小
   - **测试覆盖**: `JsonSafeModeTest` 守护安全基线（默认配置与 SafeMode 下 @type 均不实例化，含危险类名回归用例）；注意 `JSONObject extends LinkedHashMap`，断言必须用 `getClass() == JSONObject.class` 精确类型，`assertInstanceOf(HashMap.class)` 恒真无区分能力

## 相关文档

- [README.md](README.md) - 面向用户的项目说明（含预览图）
- [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [IntelliJ Platform SDK](https://plugins.jetbrains.com/docs/intellij/welcome.html)
- [API Changes List 2026](https://plugins.jetbrains.com/docs/intellij/api-changes-list-2026.html)
- [Threading Model](https://plugins.jetbrains.com/docs/intellij/threading-model.html)

## 许可证

MIT License - 详见 `LICENSE` 文件
