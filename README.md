# Spring Cloud Port Viewer（IDEA 插件）

在 IntelliJ IDEA 侧边栏以列表形式展示 Spring Cloud / Spring Boot 多模块项目中
**每个子模块的端口（`server.port`）与 context-path**，并检测**服务是否在运行**，
不用再逐个打开配置文件对端口。
<img width="1789" height="275" alt="image" src="https://github.com/user-attachments/assets/9e3af23f-ecf8-4d94-bc76-bce9b39cae3e" />



**版本要求：IDEA 2025.2（build 252）及以上，无上限。**

## 功能

- **侧边 ToolWindow**：IDEA 右侧 `Ports` 面板，可拖到任意侧/底部
- 表格列：模块名 · 服务名 · Profile · 端口 · **运行状态** · 构建文件 · 配置文件
- **点击交互**（蓝色文字列，全部单击直达，编辑器打开的同时左侧项目树同步选中该文件）：
  - 单击**模块名** → 项目树中秒定位到该模块根目录
  - 单击**服务名** → 打开 application.yml 并定位到 `spring.application.name` 所在行
  - 单击**端口** → 打开配置文件并定位到 `server.port` 所在行
  - 单击**构建文件**（`pom.xml` / `build.gradle(.kts)`）→ 编辑器直接打开
  - 单击**配置文件** → 编辑器直接打开
  - Profile / 运行状态列：无跳转
- **运行状态检测**：每 5 秒 TCP 探活端口，● 运行中（绿）/ ○ 未运行（灰），底栏显示运行数
- **多实例支持**：每个带 `-Dserver.port` / `--server.port`（大小写不敏感）的运行配置
  单独成行 —— 模块用 yml 端口和运行配置端口同时起两个实例时（如 8010 + 8011），
  两行各自独立探活，来源列显示运行配置名
- **主类识别**：没有配置文件但主类有 `@SpringBootApplication` 的模块也会列出
  （如 tlmall-frontend → `8080 (默认)`，配置文件列显示"主类检测"）
- **多 Profile 支持**：`application-dev.yml`、`application.yml` 中 `---` 多文档块
  （含 `spring.config.activate.on-profile`、旧版 `spring.profiles`）都会展开成独立行
- **默认端口推断**：没写 `server.port` 的模块显示 Spring Boot 默认 `8080 (默认)`
- **占位符解析**：`${SERVER_PORT:9001}` 显示默认值 `9001`；无默认值的显示原始占位符
- **端口冲突高亮**：按"有效端口"（含运行配置覆盖）判断冲突，红色标注
- **双击打开配置文件**；工具栏/右键菜单：浏览器访问 `http://localhost:<port>`、复制端口
- **自动刷新**：保存配置文件、模块增删、运行配置变更时自动重新扫描（400ms 防抖）

## 解析规则

扫描每个模块 `src/main/resources` 下（含自定义 main source root）：

```
application.yml / yaml / properties     # 主配置
application-<profile>.yml|properties    # profile 配置
bootstrap.yml / bootstrap-<profile>.*   # Spring Cloud 配置
```

同一 Profile 多个文件都定义端口时，按 Spring Boot 优先级取值：
`application.properties > application.yml/yaml > bootstrap.properties > bootstrap.yml/yaml`。

`.yml/.yaml` 通过 IDEA 内置 YAML 插件的 PSI 解析（正确处理多文档、引号、flow 写法），
`.properties` 为文本解析。文件在 test root 下的一律忽略。

## 构建与安装

已包含 Gradle Wrapper（9.1.0，`distributionUrl` 指向腾讯镜像，国内网络友好）。
编译目标为本机安装的 `/Applications/IntelliJ IDEA CE.app`（2025.2.4，
见 `build.gradle.kts` 中的 `local()`）—— 无需下载 IDEA SDK，产物要求 **IDEA 2025.2+**。

```bash
./gradlew buildPlugin
# 产物：build/distributions/spring-cloud-port-viewer-0.1.0.zip
```

安装到 IDEA：`Settings → Plugins → ⚙ → Install Plugin from Disk…` → 选择 zip → 重启 IDEA。

本地调试（启动带插件的沙箱 IDEA）：`./gradlew runIde`

> 想兼容更老的 IDEA（2024.x）时，把 `local(...)` 换成 `intellijIdeaCommunity("2024.2.4")`。
> 注意国内网络下 JetBrains 仓库可能解析到 `-aarch64.dmg` 布局导致编译类缺失，
> 遇到大量 `package ... does not exist` 时先清理 `~/.gradle/caches` 再重试。

## 项目结构

```
src/main/java/com/ideaser/portviewer/
├── ModulePortInfo.java            # 一行数据：(模块, profile) → 端口/contextPath/覆盖/状态
├── ConfigFileParser.java          # 单个配置文件解析（YAML PSI + properties 文本）
├── RunConfigReader.java           # 读取运行配置的 -Dserver.port 覆盖（writeExternal XML）
├── PortLivenessChecker.java       # TCP 探活：端口是否真的在监听（127.0.0.1 + ::1）
├── PortScanner.java               # 遍历模块 → 配置文件/主类识别 → 按优先级合并 → 应用覆盖
├── PortsTableModel.java           # 表格模型 + 冲突/运行状态
├── PortsPanel.java                # 面板 UI：表格/工具栏/右键菜单/自动刷新/5 秒探活轮询
└── PortViewerToolWindowFactory.java
```

## 已知限制 / Roadmap

- 运行状态是**端口级检测**：端口被其他进程占用也会显示"运行中"（通常这正是你关心的）
- 同一模块多个带端口覆盖的运行配置时，取列表中第一个（当前选中的配置优先级待做）
- 环境变量（`SERVER_PORT`）、`SPRING_APPLICATION_JSON`、`config/` 目录等外部配置不感知
- 计划：management.server.port 列；按进程 PID 关联运行配置；小图标显示多实例
