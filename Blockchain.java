import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class Blockchain {
    private final List<Block> chain = new ArrayList<>();
    private final Path file;

    public Blockchain(Path file) {
        this.file = file;
        if (Files.exists(file)) {
            load();
        } else {
            Block genesis = new Block(0, System.currentTimeMillis(), "GENESIS", "0");
            persist(genesis);
            chain.add(genesis);
        }
    }

    private void load() {
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] p = line.split("\t", 5);
                if (p.length != 5) {
                    throw new IllegalStateException("Malformed line in " + file);
                }
                Block block = new Block(Integer.parseInt(p[0]), Long.parseLong(p[1]), p[4], p[2], p[3]);
                if (block.getIndex() != chain.size()) {
                    throw new IllegalStateException("Block numbering broken at line " + (chain.size() + 1));
                }
                chain.add(block);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Malformed number in " + file);
        }
        if (chain.isEmpty()) {
            throw new IllegalStateException(file + " is empty");
        }
        int bad = findInvalidIndex();
        if (bad != -1) {
            throw new IllegalStateException("Stored chain failed validation at block " + bad);
        }
    }

    private void persist(Block block) {
        String line = block.getIndex() + "\t" + block.getTimestamp() + "\t"
                + block.getPreviousHash() + "\t" + block.getHash() + "\t" + block.getData() + "\n";
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.SYNC);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized Block addBlock(String data) {
        Block last = chain.get(chain.size() - 1);
        Block block = new Block(chain.size(), System.currentTimeMillis(), data, last.getHash());
        persist(block);
        chain.add(block);
        return block;
    }

    public synchronized int findInvalidIndex() {
        for (int i = 0; i < chain.size(); i++) {
            Block current = chain.get(i);
            if (!current.getHash().equals(current.calculateHash())) {
                return i;
            }
            if (i > 0 && !current.getPreviousHash().equals(chain.get(i - 1).getHash())) {
                return i;
            }
        }
        return -1;
    }

    public synchronized boolean isValid() {
        return findInvalidIndex() == -1;
    }

    public synchronized List<Block> getChain() {
        return new ArrayList<>(chain);
    }
}