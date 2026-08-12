[![Ask DeepWiki](https://deepwiki.com/badge.svg)](https://deepwiki.com/fc6a1b03/prism)
[![GitHub Repo stars](https://img.shields.io/github/stars/fc6a1b03/prism?style=flat&logo=github)](https://github.com/fc6a1b03/prism/stargazers)
[![GitHub total commits](https://img.shields.io/github/commit-activity/t/fc6a1b03/prism)](https://github.com/fc6a1b03/prism/commits)
[![JDK](https://img.shields.io/badge/JDK-25-green.svg)](https://www.oracle.com/java/)
[![Gradle](https://img.shields.io/badge/Gradle-9.6.1-02303A.svg)](https://gradle.org)
[![IDEA](https://img.shields.io/badge/IntelliJ_IDEA-2026.2-FF318C.svg)](https://www.jetbrains.com/idea)
[![GitHub Release](https://img.shields.io/github/v/release/fc6a1b03/prism)](https://github.com/fc6a1b03/prism/releases/latest)
[![Build](https://img.shields.io/github/actions/workflow/status/fc6a1b03/prism/build_jar.yml?branch=master)](https://github.com/fc6a1b03/prism/actions)
[![License](https://img.shields.io/github/license/fc6a1b03/prism.svg)](LICENSE)

# Prism Plugin

Prism 是一个面向 IntelliJ IDEA 2026.2 的效率插件（原名 Json Helper），集 JSON 工具与编辑器增强于一身。  
JSON 侧支持编辑、压缩、转义、JsonPath 查询、Java 类与 JSON 结构互转，以及 XML / YAML / TOML / Properties / CSV / XLSX 等格式转换；增强侧提供代码地图（minimap）、彩虹括号与变量高亮、颜色字面量、项目树文件注释与压缩包浏览、代码截图、项目 / HTTP / 端口搜索等能力。

## 当前基线

- IntelliJ IDEA: `2026.2`
- Java: `25`
- Gradle: `9.6.1`
- 安装方式: `build/distributions/*.zip` 自定义本地安装
- 插件 ID: `com.acme.prism`

## 主要能力

- JSON 编辑、格式化、压缩、转义与反转义
- **自动修复损坏 JSON**（单引号/缺逗号/尾逗号/注释/JSONP 包装/裸键/非标准字面量），带置信度与修复日志
- **按键递归排序、扁平化/还原**（点号键与嵌套结构互转）、**JSON Schema（2020-12）生成**
- **右侧面板深度工具**：结构分析（键/对象/数组/深度/大小统计、重复键检测）、树形面板展开全部/折叠全部、选中节点路径与完整值详情
- 从 JSON 生成 Java Class / Record
- 从 Java 类复制 JSON 结构
- JsonPath / JMESPath 查询与树形浏览
- URL、JWT、本地文件路径、Web 路径自动解析为 JSON
- JSON 与 XML / YAML / TOML / Properties / CSV / XLSX / Base64 / URL Params 互转
- Search Everywhere 中的项目搜索、HTTP 请求文件搜索、端口搜索与压缩包内容搜索
- 项目树中将压缩包（zip / 7z / jar / tar 等）作为目录展开浏览，条目只读打开
- 代码截图复制

## 安装

1. 运行打包命令生成插件 ZIP。
2. 在 IntelliJ IDEA 中打开 `Settings` -> `Plugins`。
3. 点击右上角齿轮按钮，选择 `Install Plugin from Disk...`。
4. 选择 `build/distributions/prism-*.zip` 安装。

## 开发与打包

本项目已生成 Gradle Wrapper（`gradlew` / `gradlew.bat`，固定 `9.6.1`），优先使用 `./gradlew`；也可使用系统中的 `gradle` 命令，Java 编译与运行环境固定为 `25`。

```bash
./gradlew printAllVersions
./gradlew test
./gradlew runIde
./gradlew clean buildPlugin
./gradlew verifyPluginProjectConfiguration verifyPluginStructure
```

打包完成后，插件 ZIP 位于：

```text
build/distributions/prism-x.x.x.zip
```

## 预览

![preview1](doc/preview1.png)
![preview2](doc/preview2.png)
![preview4](doc/preview4.png)
![preview5](doc/preview5.png)
![preview3](doc/preview3.gif)

## 项目结构

```text
src/main/java/com/acme/prism
├── common
│   └── enums
├── core
│   ├── archive      # 压缩包索引/树节点/搜索/打开器
│   ├── console      # 控制台 JSON 推送
│   ├── editor       # JSON 折叠、编辑器状态
│   ├── fileinfo     # 项目树文件信息
│   ├── json         # JSON 操作（格式化/压缩/转义/搜索/修复/排序/扁平化/Schema/分析）
│   ├── minimap      # 代码地图
│   ├── notice       # 通知系统
│   ├── parser       # 解析与格式转换
│   │   └── converter
│   ├── rainbow      # 彩虹括号/变量/颜色高亮
│   ├── screenshot   # 代码截图
│   ├── search       # 项目/HTTP/端口/压缩包搜索
│   └── settings     # 插件设置
└── ui
    ├── action       # 动作（json/搜索/截图/console）
    ├── dialog       # 转换/建类对话框
    ├── editor       # 面板编辑器定制
    ├── error        # JSON 语法错误标注
    ├── panel        # 主面板/JSON 树面板
    ├── search       # Search Everywhere 工厂
    └── statusbar    # 状态栏 JSON 路径面包屑
```

## CI / Release

GitHub Actions 工作流 [build_jar.yml](.github/workflows/build_jar.yml) 已适配当前基线：

- 固定 `Java 25`
- 固定 `Gradle 9.6.1`
- 运行 `test` 单元测试
- 构建 `buildPlugin`
- 校验 `verifyPluginProjectConfiguration` 与 `verifyPluginStructure`
- 上传 ZIP 工件并创建 GitHub Release

## 贡献

1. Fork 项目
2. 创建分支 `git checkout -b feature/your-feature`
3. 提交修改
4. 推送分支
5. 发起 Pull Request

## License

MIT

## 其他

[IDEA插件图标库](https://intellij-icons.jetbrains.design)
[IDEA插件描述](https://plugins.jetbrains.com/docs/intellij/plugin-dependencies.html#locating-plugin-id-and-preparing-sandbox)