# 放置 libxposed API 102 的 classes.jar

从 LSPosed 项目获取 API 102 的 `classes.jar`，放在本目录并重命名为 `classes.jar`。

构建时通过环境变量指定：

```bash
export LIBXPOSED_API_JAR=/path/to/classes.jar
```

或直接将 jar 放到本目录（build.sh 默认读取 `libs/classes.jar`）。

注意：jar 文件已加入 .gitignore，不会被提交到仓库。
