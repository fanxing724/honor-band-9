# 工作区使用指南 / Workspace Guide

> 面向**下一个在本环境工作的 AI** 的说明文档。读完即可直接上手，避免踩坑。

---

## 一、这是什么环境（一句话定位）

这是**阿里云 PAI-DSW（Data Science Workshop）**预装 **ModelScope** 镜像的运行实例。
工作目录 `/mnt/workspace` 是**阿里云 NAS 持久化盘**，实例重启 / 释放后数据**不丢失**。
当前是一台**纯 CPU、无 GPU** 的机器，适合做：模型下载、脚本开发、CPU 推理、数据预处理、微调代码编写；**不适合** GPU 训练/推理。

---

## 二、环境概览

| 项目 | 值 |
|------|-----|
| 操作系统 | Ubuntu 22.04.5 LTS (x86_64) |
| 当前用户 | `root` |
| Python | 3.11.11，解释器 `/usr/local/bin/python3`（系统级，**无 conda**） |
| 包管理 | `pip` / `pip3`（已装 **534** 个包） |
| Node.js | v22.22.3（`/etc/dsw/runtime/export_bin/node`） |
| Git | 2.34.1 |
| GPU | ❌ 无（`nvidia-smi` 不可用）；`torch` 是 **cpu** 版 (`2.3.1+cpu`) |
| CPU | 64 核 |
| 内存 | 28 GB |
| 磁盘 | `/mnt/workspace` 为阿里云 NAS，总量 1.0P、可用 ~990T、已用 4% —— **持久化** |
| 平台 | 阿里云 PAI-DSW 运行时（含 Jupyter / Code-Server / Web Terminal UI） |

---

## 三、关键目录（务必分清“持久”与“临时”）

| 路径 | 说明 | 持久？ |
|------|------|--------|
| `/mnt/workspace` | **唯一持久化盘（NAS）**。代码、数据、模型缓存都应放这里 | ✅ 是 |
| `/mnt/workspace/.cache/modelscope` | ModelScope 模型下载缓存（已映射到工作盘，下载一次永久保留） | ✅ 是 |
| `/mnt/workspace/.local/share` | DSW 系统与插件持久化目录 | ✅ 是 |
| `/mnt/workspace/番星（数据保留，读取快速启动）/` | 用户预留的**数据保留目录**，适合放需长期保存并快速读取的数据集（当前为空） | ✅ 是 |
| `/root`、`/etc/dsw` 等 | 实例临时目录，重启会被重置 | ❌ 否 |

> ⚠️ **铁律**：任何重要产出（代码、模型、数据、配置）一律写入 `/mnt/workspace` 的子目录。
> 不要依赖 `/root` 或 `/etc/dsw` 的持久性。

---

## 四、已预装的主要库

- **ModelScope 生态**：`modelscope 1.38.0`、`modelscope-hub 0.1.3`
- **NLP / 训练**：`transformers 4.55.4`、`datasets 3.6.0`、`tokenizers 0.21.4`、
  `accelerate 1.12.0`、`peft 0.18.1`、`ms-swift 3.11.3`（微调）、`sentence-transformers 5.2.0`
- **深度学习（CPU 版）**：`torch 2.3.1+cpu`、`torchvision 0.18.1+cpu`、`torchaudio 2.3.1+cpu`
- **多模态 / 生成**：`diffusers 0.34.0`、`opencv-python`、`pillow`、`funasr 1.3.0`（语音）
- **工具 / 可视化**：`gradio 5.50.0`、`pandas 2.2.3`、`numpy 1.26.4`、`scikit-learn 1.8.0`
- **未安装**：`vllm`（如需可 `pip install`，且 `VLLM_USE_MODELSCOPE` / `LMDEPLOY_USE_MODELSCOPE` 已设置，会从 ModelScope 拉模型）

---

## 五、网络连通性（重要）

| 目标 | 状态 | 说明 |
|------|------|------|
| `modelscope.cn` | ✅ 可达 (HTTP 200) | 模型主源，正常用 |
| `pypi.org` | ✅ 可达 (HTTP 200) | `pip install` 可联网安装 |
| `huggingface.co` | ❌ 不可达（超时） | **不要从这里下载模型/数据集** |

> **结论**：下载模型请走 **ModelScope**，不要走 HuggingFace。

---

## 六、给下一个 AI 的操作建议（踩坑清单）

1. **持久化**：所有产出写进 `/mnt/workspace` 子目录；别往 `/root`、`/etc/dsw` 放重要东西。
2. **下载模型**：用 `modelscope download` 或 Python
   `from modelscope import snapshot_download`，缓存自动落到 `/mnt/workspace/.cache/modelscope`。
3. **无 GPU**：不要假设有显卡。需要 GPU 推理 / 训练本实例完成不了，应明确提示用户换卡或加 GPU 实例。
4. **优先复用**：`pip install` 虽可用，但优先复用已装生态（modelscope / transformers / ms-swift 等），减少重复安装。
5. **大数据集**：建议放 `/mnt/workspace/番星（数据保留，读取快速启动）/` 目录，读取快且保留。
6. **已登录态**：`MODELSCOPE_ACCESS_TOKEN` 已注入，ModelScope 私有模型一般可直接拉取，无需重新登录。

---

## 七、常用命令

```bash
# Python 环境
python3 --version            # 3.11.11
pip list | grep -i modelscope

# 下载模型（命令行）
modelscope download --model_id <模型ID> --local_dir /mnt/workspace/models/<模型名>

# 下载模型（Python）
python3 -c "from modelscope import snapshot_download; \
snapshot_download('模型ID', cache_dir='/mnt/workspace/.cache/modelscope')"

# 查看持久盘空间
df -h /mnt/workspace

# 安装额外依赖（可联网）
pip install <package>
```

---

## 八、已注入的环境变量（无需手动设置）

| 变量 | 含义 |
|------|------|
| `MODELSCOPE_CACHE=/mnt/workspace/.cache/modelscope` | 模型缓存目录（持久） |
| `MODELSCOPE_ACCESS_TOKEN` | ModelScope 登录态（已设置） |
| `VLLM_USE_MODELSCOPE` / `LMDEPLOY_USE_MODELSCOPE` | 让 vLLM / LMDeploy 从 ModelScope 拉模型 |
| `DSW_*` 系列 | 阿里云 PAI-DSW 运行时相关（端口、缓存、插件等） |

---

*文档生成于 2026-07-23，由 AI 自动采集环境信息后编写，供后续 AI 接手本工作区使用。*

