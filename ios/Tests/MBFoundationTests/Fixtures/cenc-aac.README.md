# CENC 音频测试样本

`cenc-aac.m4a` 是本项目使用 FFmpeg 生成的 0.15 秒 440 Hz 合成正弦波，
不含平台歌曲、账号或真实内容密钥。固定测试密钥与 KID 仅用于合成测试。

```sh
ffmpeg -f lavfi -i 'sine=frequency=440:duration=0.15' -c:a aac \
  -encryption_scheme cenc-aes-ctr \
  -encryption_key 00112233445566778899aabbccddeeff \
  -encryption_kid 000102030405060708090a0b0c0d0e0f cenc-aac.m4a
```

AAC 编码包含预滚样本；回归比较解码前后的实际容器时长，并用系统解码器读取 PCM。
