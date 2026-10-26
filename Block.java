import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public class Block {
    private final int index;
    private final long timestamp;
    private String data;
    private final String previousHash;
    private String hash;

    public Block(int index, long timestamp, String data, String previousHash) {
        this.index = index;
        this.timestamp = timestamp;
        this.data = data;
        this.previousHash = previousHash;
        this.hash = calculateHash();
    }

    public Block(int index, long timestamp, String data, String previousHash, String hash) {
        this.index = index;
        this.timestamp = timestamp;
        this.data = data;
        this.previousHash = previousHash;
        this.hash = hash;
    }

    public String calculateHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String input = index + "\n" + timestamp + "\n" + data + "\n" + previousHash;
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public int getIndex() { return index; }
    public long getTimestamp() { return timestamp; }
    public String getData() { return data; }
    public String getPreviousHash() { return previousHash; }
    public String getHash() { return hash; }

    public void setData(String data) { this.data = data; }
}