# 精简版光谱图像数据库

当前数据库只保留 5 张核心业务表：

| 表 | 作用 |
|---|---|
| `t_spectral_capture` | 一次独立的8秒触发采集 |
| `t_spectral_image` | 原始图、预览图和处理图的文件地址 |
| `t_image_integrity_analysis` | 超时、尺寸、格式和CRC完整性结果 |
| `t_image_quality_analysis` | 最重要的图像质量指标 |
| `t_image_action_log` | 接受、校正、重拍、丢弃和报警记录 |

用户表 `t_user` 保持不变。

## 为什么不直接把图片放进PostgreSQL

一张 `800 x 600 x 16bit` 原始图约为 `960000 bytes`。每8秒一张时，一天原始数据约为
9.66 GiB。因此：

- 原始12-bit数据保存到本地目录、MinIO、OSS或S3；
- PostgreSQL只保存文件地址和SHA-256；
- 前端8-bit预览图同样使用文件地址；
- 经过校正的图像保存在`processed_storage_uri`。

当前本地文件目录配置为：

```properties
spectral.storage.root=D:/GraduationProject/spectral-images
```

该目录与`jiziwai`、`SpectraBridge`同级，不放在代码工程内部。

## 精简原则

以下内容暂不单独建表：

- 质量规则版本；
- 标定档案；
- 坏点和异常区域明细；
- 每条谱线明细；
- 完整直方图；
- Keystone、鬼影等尚未实现的指标。

它们可以先保存在各表的`details JSONB`字段中。只有在算法真正实现、查询需求稳定后，才考虑拆成独立表。

## 核心流程

```text
触发采集
  -> t_spectral_capture
  -> 接收成功时写入 t_spectral_image
  -> t_image_integrity_analysis
  -> 完整性通过后写入 t_image_quality_analysis
  -> 最终动作写入 t_image_action_log
```

完整性失败时可以没有`t_spectral_image`记录。例如：

- FPGA返回错误；
- 等待超时；
- TCP连接中断。

## 阈值保存位置

当前阶段建议将阈值放在Spring Boot配置或分析模块的版本化配置文件中，而不是数据库：

```yaml
spectral:
  quality:
    saturation-warning-ratio: 0.001
    saturation-fail-ratio: 0.01
```

芯片手册阈值、工程阈值和合格样机基准必须在配置文件中注明来源。等系统需要在线修改阈值时，再增加规则表。

## SQL文件

- `schema.sql`：精简后的完整建表脚本；
- `simplify_image_schema.sql`：从旧结构迁移时删除旧图像表，保留用户表。

数据库已完成一次迁移，后续新环境只需要执行`schema.sql`。
