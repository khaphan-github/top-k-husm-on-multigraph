package topk.husm.mg;

import topk.husm.mg.graph.MgGraphLoader;
import topk.husm.mg.graph.MultiGraph;
import topk.husm.mg.graph.VertexLabelMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MgVertexLabelTest {

    @Test
    void randomLabelsAreReproducibleAndInRange(@TempDir Path dir) throws IOException {
        writePath(dir);
        MgGraphLoader.Loaded a = MgGraphLoader.load(
                dir.resolve("path.txt"), 5, VertexLabelMode.RANDOM, 42);
        MgGraphLoader.Loaded b = MgGraphLoader.load(
                dir.resolve("path.txt"), 5, VertexLabelMode.RANDOM, 42);
        MgGraphLoader.Loaded c = MgGraphLoader.load(
                dir.resolve("path.txt"), 5, VertexLabelMode.RANDOM, 43);

        assertArrayEquals(a.vertexLabels(), b.vertexLabels());
        assertFalse(java.util.Arrays.equals(a.vertexLabels(), c.vertexLabels()));
        for (int label : a.vertexLabels()) {
            assertTrue(label >= 0 && label < 5, "label outside [0,5): " + label);
        }
    }

    @Test
    void degreeLabelsRetainEqualFrequencyBuckets(@TempDir Path dir) throws IOException {
        writePath(dir);
        MgGraphLoader.Loaded l = MgGraphLoader.load(
                dir.resolve("path.txt"), 2, VertexLabelMode.DEGREE, 42);
        assertArrayEquals(new int[] {0, 0, 1, 1, 0}, l.vertexLabels());
    }

    @Test
    void labelModeDoesNotChangeEdgeWeights(@TempDir Path dir) throws IOException {
        writePath(dir);
        MultiGraph random = MgGraphLoader.build(
                dir.resolve("path.txt"), 3, 42, "gaussian", VertexLabelMode.RANDOM);
        MultiGraph degree = MgGraphLoader.build(
                dir.resolve("path.txt"), 3, 42, "gaussian", VertexLabelMode.DEGREE);

        assertEquals(degree.vertexCount(), random.vertexCount());
        assertArrayEquals(degree.allWeightsFlat(), random.allWeightsFlat());
    }

    private static void writePath(Path dir) throws IOException {
        Files.writeString(dir.resolve("path.txt"), """
                0 1 0
                1 2 0
                2 3 0
                3 4 0
                """);
    }
}
