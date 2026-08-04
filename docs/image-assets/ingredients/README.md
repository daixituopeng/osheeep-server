# 食材图片资产维护

食材图片只使用允许商业使用和再分发的 Wikimedia Commons 文件。每张图片都必须在 `manifest.json` 中记录来源文件名、作者与许可；下载后生成的 `metadata.json` 还会记录来源页、原文件地址、采集日期、SHA-256、尺寸和对象键。

## 资产规则

- 不在小程序中热链第三方图片；审核后的文件由自己的服务提供。
- 优先使用 CC0 或公有领域素材；CC BY、CC BY-SA 素材必须保留作者、来源页和许可链接。
- `original.jpg` 是采集归档副本，不直接公开。
- 列表图统一为 640 × 400 WebP，详情图统一为 1280 × 800 WebP。
- 图片主体必须能在 8:5 中心裁切后清楚识别，不使用带明显品牌包装、水印或误导性摆拍的素材。
- 找不到准确图片时，客户端显示分类色兜底，不拿相似食材冒充。

## 同步命令

需要本机安装 `cwebp`：

```sh
python3 scripts/sync-ingredient-images.py \
  --manifest docs/image-assets/ingredients/manifest.json \
  --output-root docs/image-assets/ingredients \
  --static-root src/main/resources/static/media/ingredients \
  --download --process
```

替换单张图片时，先更新清单，再使用 `--force --slug <slug>`。人工检查生成结果后，更新数据库迁移或资产维护记录。

生产数据通过 `dinner_ingredients.image_asset_id` 关联 `dinner_image_assets`；客户端只消费接口返回的 `imageUrl`。
