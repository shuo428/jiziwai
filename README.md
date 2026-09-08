# 光谱图像采集与分析系统开发说明

本目录是光谱图像采集与分析系统的前端、Java 后端和智能体相关代码。

系统整体由三个主要部分组成：

- `jiziwai`：前端、Spring Boot 后端、数据库访问、图像质量分析、校准、处理、光谱提取、智能体入口。
- `SpectraBridge`：C++ / JNI / FPGA TCP 通信、图像接收完整性校验、GLUX1605 读出顺序重排、Mock FPGA 测试工具。
- `spectral-images`：运行期图像、RAW16、预览图、校准包、缺陷地图、光谱文件的外部存储目录。

发布给其他电脑使用时，优先使用：

```text
D:\GraduationProject\release\SpectralSystem-docker-20260825\SpectralSystem\README.txt
```

该文档是面向最终使用者的 Windows Docker 运行包说明。


## 一、当前系统能力

目前已经实现的核心功能包括：

- C++ 端图像接收完整性校验。
- 普通单平面图像采集。
- GLUX1605 4-lane HDR 读出顺序重排。
- HDR 双增益 HG / LG 拆分、重排和融合。
- Java 端图像入库和文件外部存储。
- raw / calibrated / processed 图像版本管理。
- 单张图像基础质量指标分析。
- 图像质量状态判断：PASS / WARNING / FAIL。
- 质量处置策略。
- 坏点插值修复。
- 异常行、异常列修复。
- 暗场采集、平场采集。
- 多帧中值暗场/平场参考图生成。
- 稳定缺陷地图生成与修复。
- 普通模式和 HDR 模式下的校准包管理。
- 原始 RAW16 / 重排后 RAW16 / HDR payload 像素数据查看。
- 二维光谱几何校正第一版/第二版：ROI、方向自动判断/指定、旋转、翻转、轻微倾斜整数像素矫正。
- 一维光谱基础提取与曲线显示。
- 光谱数据管理页面。
- 设备总览、配置管理、普通模式、HDR 模式的前端页面拆分。
- Mock FPGA 测试流程。


## 二、开发环境要求

源码开发建议环境：

- Windows x64
- Java 8 或更高版本
- Maven 3.x
- Node.js 22.13.1 或兼容版本
- pnpm 10.x 或 npm
- PostgreSQL
- Docker Desktop，可选，用于快速启动 PostgreSQL 或验证发布包

检查 Java：

```powershell
java -version
```

检查 Maven：

```powershell
mvn -version
```

检查前端包管理器：

```powershell
pnpm -v
```


## 三、数据库配置

开发环境中的后端配置文件是：

```text
D:\GraduationProject\jiziwai\springboot-jni\src\main\resources\application.properties
```

当前开发默认数据库连接为：

```properties
spring.datasource.url=jdbc:postgresql://localhost:5433/jiziwai
spring.datasource.username=postgres
spring.datasource.password=123456
```

如果你使用发布包里的 Docker PostgreSQL，则发布包默认端口是：

```text
55433
```

注意：

源码开发配置和发布包配置不是同一个文件。

发布包运行时使用：

```text
SpectralSystem\.env
```

并由 `start.bat` 将 `.env` 中的数据库端口传给 Java 后端。

数据库建表脚本位于：

```text
D:\GraduationProject\jiziwai\springboot-jni\src\main\resources\db\schema.sql
```

发布包中会复制为：

```text
SpectralSystem\db\schema.sql
```

首次启动且数据库目录为空时，PostgreSQL 容器会自动执行该脚本。


## 四、后端开发启动

进入后端目录：

```powershell
cd D:\GraduationProject\jiziwai\springboot-jni
```

编译：

```powershell
mvn -q -DskipTests compile
```

启动：

```powershell
mvn spring-boot:run
```

后端默认运行在：

```text
http://localhost:8080
```

如果本机数据库端口不是 `5433`，可以临时覆盖：

```powershell
mvn spring-boot:run -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:postgresql://localhost:55433/jiziwai"
```


## 五、前端开发启动

进入前端目录：

```powershell
cd D:\GraduationProject\jiziwai\frontend
```

安装依赖：

```powershell
pnpm install
```

启动开发服务器：

```powershell
pnpm dev
```

前端开发地址：

```text
http://localhost:5173
```

前端开发代理配置在：

```text
D:\GraduationProject\jiziwai\frontend\vite.config.js
```

当前代理关系：

```text
/api       -> http://127.0.0.1:8080
/ws        -> ws://127.0.0.1:8080
/agent-api -> http://127.0.0.1:8001
```


## 六、生产构建

前端构建：

```powershell
cd D:\GraduationProject\jiziwai\frontend
pnpm build
```

后端打包：

```powershell
cd D:\GraduationProject\jiziwai\springboot-jni
mvn -q -DskipTests package
```

生成的 jar 位于：

```text
D:\GraduationProject\jiziwai\springboot-jni\target\springboot-jni-0.0.1-SNAPSHOT.jar
```

发布包中使用的 jar 会复制为：

```text
SpectralSystem\backend\springboot-jni.jar
```

运行包还需要包含：

```text
SpectralSystem\backend\SpectraBridgeJni.dll
SpectralSystem\frontend
SpectralSystem\db\schema.sql
SpectralSystem\tools\spectra_bridge_test.exe
```


## 七、图像和数据保存策略

数据库只保存图像、校准、缺陷地图、光谱文件的元数据和相对路径。

开发环境默认外部存储目录：

```text
D:\GraduationProject\spectral-images
```

发布包运行时默认外部存储目录：

```text
SpectralSystem\data\spectral-images
```

图像版本逻辑：

- `raw`：从 FPGA 接收到并通过完整性校验后的原始图像数据。
- `calibrated`：应用暗场扣除、平场校正、稳定缺陷修复后的图像。
- `processed`：在质量处置策略允许的情况下进一步处理后的图像。
- `geometry-corrected`：在 raw / calibrated / processed 中选择一个 PASS 输入后，按 ROI、方向、旋转/翻转和轻微倾斜矫正生成的二维光谱校正图。

后续质量分析、光谱提取会根据当前图像状态选择合适版本。


## 八、光谱几何校正

当前已经实现第一版/第二版几何校正，入口在：

```text
光谱几何校正
```

可配置内容：

- 输入图像版本：自动、原图、校准后、处理后。
- 波长方向：自动判断、X 横向、Y 纵向。
- 图像方向：0/90/180/270 度旋转、水平翻转、垂直翻转。
- 有效 ROI：xStart、xEnd、yStart、yEnd，未填写时默认整图。
- 轻微倾斜矫正：在 ROI 内通过行/列一维谱形互相关估计整数像素偏移，再保存校正后 RAW16 和 PNG 预览。

几何校正结果保存规则：

- 同一张图像只保留最新一次几何校正记录。
- 原始 raw、calibrated、processed 文件不会被覆盖。
- 一维光谱提取使用 `AUTO` 来源时，会优先使用该图像最新的 geometry-corrected 结果；也可以显式指定 `GEOMETRY_CORRECTED`。


## 九、采集模式说明

普通模式：

- 面向单平面图像。
- 可以处理普通行优先 RAW16。
- 也可以处理 GLUX1605 4-lane 交织读出的单平面图像。
- 可进行普通暗场/平场校准包管理。

HDR 模式：

- 面向一次触发返回 HG + LG 双平面图像。
- 当前约定 payload 顺序为：

```text
HG 完整平面
LG 完整平面
```

- 系统会先拆分 HG/LG。
- 再分别按配置进行读出顺序重排。
- 再进行 HDR 融合。
- 融合图像之后复用普通图像的质量分析、处理和一维光谱提取流程。
- HDR 暗场/平场会分别生成 HG/LG 参考图和缺陷地图。


## 十、Mock FPGA 测试

Mock FPGA 工具位于：

```text
D:\GraduationProject\SpectraBridge\tools\mock-fpga
```

发布包中位于：

```text
SpectralSystem\tools
```

常用测试场景：

```powershell
spectra_bridge_test.exe --scene normal
spectra_bridge_test.exe --scene hdr
spectra_bridge_test.exe --scene dark
spectra_bridge_test.exe --scene flat
spectra_bridge_test.exe --scene hdr-dark
spectra_bridge_test.exe --scene hdr-flat
```

默认端口：

```text
控制端口 5000
图像端口 5001
```

前端连接时：

```text
Host = 127.0.0.1
控制端口 = 5000
图像端口 = 5001
```

注意：

普通采集页面要对应 `--scene normal`。
HDR 采集页面要对应 `--scene hdr`。
普通暗场/平场页面要对应 `--scene dark` 或 `--scene flat`。
HDR 暗场/平场页面要对应 `--scene hdr-dark` 或 `--scene hdr-flat`。


## 十一、常见开发问题

1. 后端无法连接数据库

检查 PostgreSQL 是否启动，端口是否和 `application.properties` 一致。

2. 前端页面请求失败

确认后端运行在：

```text
http://localhost:8080
```

并检查 `vite.config.js` 中的代理配置。

3. JNI DLL 加载失败

确认 DLL 路径和 Java 启动参数：

```text
-Djava.library.path=...
```

发布包中由 `start.bat` 自动设置。

4. 普通采集和 HDR 采集模式不匹配

普通采集期望单平面图像。
HDR 采集期望 HG/LG 双平面图像。

5. 图像尺寸或读出顺序不一致

先到配置管理确认：

```text
图像宽度
图像高度
像素格式
读出顺序
```

真实 FPGA 联调时，Java 前端配置、C++ 接收解析、FPGA 实际发送格式必须一致。


## 十二、仍需继续完善的方向

后续重点工作包括：

- 真实 CMOS / FPGA 联调。
- GLUX1605 真实 HDR 数据进一步验证。
- 波长标定。
- 系统化光谱预处理。
- 光谱拟合与分析。
- 智能体功能完善。
- 更完整的部署和安装包方案。
