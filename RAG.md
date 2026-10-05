# Yallmo local RAG

`yallmo` is a JVM-only local RAG library. It provides ZIO capabilities for tokenization, ONNX embedding inference, vector indexing, and source-attributed retrieval. The first index is an exact in-memory cosine index for small lore corpora and is rebuilt for each process.

## ONNX demo

The demo expects a BERT-compatible sentence-transformer ONNX model whose first output is `last_hidden_state`, plus its matching `vocab.txt`. The included tokenizer supports the WordPiece vocabulary used by the English `all-MiniLM-L6-v2` model.

Download model artifacts outside version control (the `target/` directory is suitable). For reproducible deployments, use a reviewed immutable model revision and verify its checksum rather than `main`.

```bash
mkdir -p target/yallmo-demo/all-MiniLM-L6-v2
curl -L -o target/yallmo-demo/all-MiniLM-L6-v2/model.onnx \
  https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx
curl -L -o target/yallmo-demo/all-MiniLM-L6-v2/vocab.txt \
  https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt

sbt 'run -- --model target/yallmo-demo/all-MiniLM-L6-v2/model.onnx --vocab target/yallmo-demo/all-MiniLM-L6-v2/vocab.txt --query "What does Norse mythology say about ravens and Odin?"'
```

The default source directory is `data/lores`; override it with `--source <directory>`. Top-level Markdown files are ingested, excluding a directory `README.md`. A small deterministic smoke-test corpus and expected queries live in [`data/rag-demo`](../data/rag-demo/README.md).

The demonstration does not persist embeddings. Production integration should add a versioned index manifest, a corpus/update policy, and an explicit per-agent retrieval allowlist.
