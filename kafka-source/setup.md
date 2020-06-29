```sh
git clone https://github.com/apache/kafka.git

# 下载和更新 Gradle 套件
gradle

# 生成 Jar 文件
./gradlew jar
```

## 目录结构
bin 目录：保存 Kafka 工具行脚本
clients 目录：保存 Kafka 客户端代码
config 目录：保存 Kafka 的配置文件
connect 目录：保存 Connect 组件的源代码
core 目录：保存 Broker 端代码
streams 目录：保存 Streams 组件的源代码

```sh
# 测试 Broker 端代码
./gradlew core:test

# 测试 Clients 端代码
./gradlew clients:test

# 测试 Connect 端代码
./gradlew connect:[submodule]:test

# 测试 Streams 端代码
./gradlew streams:test
```

单独对某一个具体的测试用例进行测试
```sh
./gradlew core:test --tests kafka.log.LogTest
```

构建整个 Kafka 工程并打包出一个可运行的二进制环境
```sh
./gradlew clean releaseTarGz
```
