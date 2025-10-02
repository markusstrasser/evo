What I find difficult with RAG is what I call the “cascading failure problem”.

1. Chunking can fail (split tables) or be too slow (especially when you have to ingest and chunk gigabytes of data in real-time)

2. Embedding can fail (wrong similarity)

3. BM25 can fail (term mismatch)

4. Hybrid fusion can fail (bad weights)

5. Reranking can fail (wrong priorities)

Each stage compounds the errors of the previous stage. Beyond the complexity of hybrid search itself, there’s an infrastructure burden that’s rarely discussed.
