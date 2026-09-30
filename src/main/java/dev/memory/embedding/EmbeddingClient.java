package dev.memory.embedding;

public interface EmbeddingClient {
    Embedding embed(String content);

    record Embedding(float[] values, String provider, String model, int dimensions, String version) {
        public Embedding { values = values.clone(); }
        @Override public float[] values() { return values.clone(); }
        public String vectorLiteral() {
            return java.util.Arrays.toString(values);
        }
    }
}
