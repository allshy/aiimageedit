> ## Documentation Index
> Fetch the complete documentation index at: https://platform.stepfun.com/docs/llms.txt
> Use this file to discover all available pages before exploring further.

# 图片编辑

<Callout type="info">step-1x-edit 模型限时免费调用中</Callout>
图片编辑 API 可基于用户输入的图片和 Prompt 对图片进行修改。



我的apikey：不要把 API Key 明文写进文档；请放到本地 `.env` 或程序输入框。

### 请求地址

`POST https://api.stepfun.com/v1/images/edits`

### 请求参数

* `model` `string` ***required*** <br />需要使用的模型名称，当前支持：
  * `step-image-edit-2`（推荐）
  * `step-1x-edit`

* `image` `file` ***required*** <br />传入的图片文件，当前仅支持传入一个图片。不同模型的输入限制：
  * `step-image-edit-2`：最大支持 4096x4096 分辨率的输入图；支持传入图片的 Base64。
  * `step-1x-edit`：图片分辨率最小为 64px，最大为 1728 像素，最大像素面积不可超过 1024x1024；支持文件格式 jpg、png、webp；图片尺寸大小 10MB；文件尺寸比例需大于等于 1:3 且小于等于 3:1。

* `prompt` `string` ***required*** <br />图像的文本描述，最大长度为 512 个字符。

* `seed` `int` ***optional*** <br />随机种子。
  * `step-image-edit-2`：取值范围 `[0, 2147483647]`；若不传，服务端会随机生成一个种子。
  * `step-1x-edit`：当不传或传入为 0 时，使用系统随机生成的种子。

* `steps` `int` ***optional*** <br />生成步数。
  * `step-image-edit-2`：取值范围 `[1, 50]`。默认为 8。
  * `step-1x-edit`：当前支持 1 ～ 100 之间整数。默认为 28。

* `cfg_scale` `float` ***optional*** <br />classifier-free guidance scale。
  * `step-image-edit-2`：必须 >= 1.0，取值范围 `[1.0, 10.0]`。默认为 1.0。
  * `step-1x-edit`：当前支持 1 ～ 10 之间的数字。默认为 6。

* `size` `string` ***optional*** <br />
  * `step-image-edit-2`：编辑场景下该参数不生效，会返回和输入图一样大小的结果图。
  * `step-1x-edit`：生成图片的大致尺寸，默认值为 `512x512`；可选项 `512x512`、`768x768`、`1024x1024`。尺寸逻辑： 1. 当图片输入为 1:1 时，会按照 size 进行输出； 2. 当图片输入比例不为 1:1 时，会按照用户提供的图片计算比例，且输出图像尺寸面积约等于 size \* size。

* `negative_prompt` `string` ***optional*** <br />负面提示词，仅 `step-image-edit-2` 支持。字符数不超过 512，默认 `""`。若 `cfg_scale = 1.0`，当前实现不会把负面提示词传给底层模型。

* `text_mode` `bool` ***optional*** <br />针对文字场景的优化策略，仅 `step-image-edit-2` 支持。默认 `False`，按需开启。

* `response_format` `string` ***optional*** <br />
  生成的图片返回的格式。支持参数为 `b64_json` 或 `url`。默认为 `url`。

### 请求响应

* `created` `int`
  <br />
  创建图片时的时间戳，精确到秒级别
* `data` `object array`
  <br />
  计算 token 返回数据 - `seed` `int`
  <br />
  生成时传入的 Seed 或系统随机生成的 Seed。相同的 Seed 有助于生成类似的图片。 - `finish_reason` `string`
  <br />
  生成停止的原因，如果为 success ，则为成功生成；为 content\_filtered 表示生成成功，但命中检测所以停止。 - `b64_json`
  `string`
  <br />
  生成的图片的 Base64 编码。当 response\_format 设置为 b64\_json 时，返回此字段。 - `url` `string`
  <br />
  生成的图片的下载链接。当 response\_format 设置为 url 时，返回此字段。

```json theme={null}
{
  "created": 1589478378,
  "data": [
    {
      "b64_json": "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1",
      "finish_reason": "success",
      "seed": 123838
    }
  ]
}
```

### 示例

<Tabs>
  <Tab title="python">
    ```python theme={null}
    import base64
    from openai import OpenAI

    client = OpenAI(api_key=STEP_API_KEY, base_url="https://api.stepfun.com/v1")
    
    prompt = """
    变成一只英短猫
    """
    
    result = client.images.edit(
        model="step-1x-edit",
        image=open("cat.jpg", "rb"),
        prompt=prompt,
        response_format="b64_json",
        extra_body={
            "cfg_scale": 10.0,
            "steps": 20,
            "seed": 1
        },
    )
    
    print(result)
    image_base64 = result.data[0].b64_json
    image_bytes = base64.b64decode(image_base64)
    
    # Save the image to a file
    with open("cat-on-rooftop.png", "wb") as f:
        f.write(image_bytes)
    ```
  </Tab>

  <Tab title="js">
    ```js theme={null}
    import fs from "fs";
    import OpenAI, { toFile } from "openai";

    const client = new OpenAI({
        apiKey: STEP_API_KEY,
        baseURL: "https://api.stepfun.com/v1"
    });
    
    const file = await toFile(fs.createReadStream("cat.jpg"), null, {
        type: "image/jpeg"
    });
    
    const rsp = await client.images.edit({
        model: "step-1x-edit",
        image: file,
        prompt: "变成一只英短猫",
        // @ts-expect-error this is not yet public
        cfg_scale: 10.0,
        steps: 20,
        seed: 1,
        response_format: "b64_json"
    });
    
    console.log(rsp);
    
    // Save the image to a file
    const image_base64 = rsp.data[0].b64_json;
    const image_bytes = Buffer.from(image_base64, "base64");
    fs.writeFileSync("cat2.jpg", image_bytes);
    ```
  </Tab>

  <Tab title="curl">
    ```bash theme={null}
    curl -X POST "https://api.stepfun.com/v1/images/edits" \
      -H "Authorization: Bearer $STEP_API_KEY" \
      -F "model=step-1x-edit" \
      -F "image=@cat.jpg" \
      -F "response_format=url" \
      -F 'prompt=变成一只英短'
    ```
  </Tab>

  <Tab title="curl (step-image-edit-2)">
    ```bash theme={null}
    curl -X POST "https://api.stepfun.com/v1/images/edits" \
      -H "Authorization: Bearer $STEP_API_KEY" \
      -F 'model=step-image-edit-2' \
      -F 'image=@test_cref_input02_resized.webp' \
      -F 'prompt=让图中角色骑自行车，手上举个牌子写着"沙特阿拉伯"' \
      -F 'response_format=b64_json' \
      -F 'cfg_scale=1.0' \
      -F 'steps=8' \
      -F 'seed=1' \
      -F 'text_mode=true'
    ```
  </Tab>
</Tabs>

```json filename="返回" theme={null}
{
  "id": "745f66ca7f11cd5424f06b106e2e5bed.5c51cd6a807226cbd4a88c4bffe8aa38",
  "created": 1752565891,
  "data": [
    {
      "url": "https://res.stepfun.com/image_gen/20250715/sample.png",
      "finish_reason": "success",
      "seed": 1
    }
  ]
}
```
