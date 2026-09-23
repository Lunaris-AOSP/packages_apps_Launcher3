package com.android.launcher3.search.universal;

import android.content.Context;
import android.graphics.Bitmap;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.Tensor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class PhotoLabeler implements AutoCloseable {

    static final int SIZE = 224;
    private static final String MODEL = "search/mobilenet_v2_quant.tflite";
    private static final String LABELS = "search/imagenet_labels.txt";
    private static final float MIN_CONFIDENCE = 0.15f;
    private static final int MAX_LABELS = 3;

    /**
     * {first, last, words...} over ImageNet class ids (label file index minus the background).
     * A photo is one kind of animal at most: rows here compete and only the likeliest kind is
     * kept, which stops fluffy kittens being tagged "dog" through a runner-up breed.
     */
    private static final Object[][] ANIMALS = {
            {0, 6, "fish", "animal"},
            {7, 24, "bird", "animal"},
            {80, 100, "bird", "animal"},
            {127, 146, "bird", "animal"},
            {25, 68, "reptile", "animal"},
            {151, 268, "dog", "pet", "animal"},
            {281, 285, "cat", "pet", "animal"},
            {286, 293, "cat", "animal"},
            {294, 297, "bear", "animal"},
            {300, 326, "insect", "animal"},
            {330, 332, "rabbit", "animal"},
    };
    /** Like {@link #ANIMALS}, but these can all apply to the same photo. */
    private static final Object[][] SCENES = {
            {923, 969, "food"},
            {970, 980, "nature", "outdoors"},
            {985, 986, "flower", "nature"},
    };
    private static final Object[][] EXTRA_WORDS = {
            {new int[]{407, 436, 468, 511, 609, 627, 656, 661, 675, 717, 734, 751, 817, 864},
                    "car", "vehicle"},
            {new int[]{978, 977}, "beach", "sea"},
            {new int[]{970, 979, 980}, "mountain"},
            {new int[]{975}, "lake"},
            {new int[]{963}, "pizza"},
            {new int[]{933}, "burger"},
            {new int[]{444, 671, 870}, "bike"},
            {new int[]{895, 404}, "plane"},
            {new int[]{510, 628, 724, 780, 814, 914}, "boat"},
            {new int[]{967, 968}, "coffee"},
    };
    private static final float MIN_GROUP_CONFIDENCE = 0.4f;

    static boolean isCategoryWord(String word) {
        String w = word.toLowerCase(Locale.ROOT).trim();
        String singular = w.endsWith("s") && w.length() > 3 ? w.substring(0, w.length() - 1) : w;
        for (Object[][] table : new Object[][][]{ANIMALS, SCENES, EXTRA_WORDS}) {
            for (Object[] row : table) {
                for (Object value : row) {
                    if (value instanceof String s && (s.equals(w) || s.equals(singular))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private final Interpreter mInterpreter;
    private final String[] mLabels;
    private final ByteBuffer mInput;
    private final byte[][] mOutput;
    private final float mScale;
    private final int mZeroPoint;
    private final int[] mPixels = new int[SIZE * SIZE];

    PhotoLabeler(Context context) throws IOException {
        ByteBuffer model;
        try (InputStream in = context.getAssets().open(MODEL)) {
            byte[] bytes = in.readAllBytes();
            model = ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());
            model.put(bytes).rewind();
        }
        List<String> labels = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open(LABELS), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                labels.add(line.trim());
            }
        }
        mLabels = labels.toArray(new String[0]);
        mInterpreter = new Interpreter(model, new Interpreter.Options().setNumThreads(2));
        Tensor input = mInterpreter.getInputTensor(0);
        Tensor output = mInterpreter.getOutputTensor(0);
        if (input.dataType() != DataType.UINT8 || output.dataType() != DataType.UINT8
                || output.shape()[1] != mLabels.length) {
            mInterpreter.close();
            throw new IOException("Unexpected photo model shape");
        }
        mInput = ByteBuffer.allocateDirect(SIZE * SIZE * 3).order(ByteOrder.nativeOrder());
        mOutput = new byte[1][mLabels.length];
        mScale = output.quantizationParams().getScale();
        mZeroPoint = output.quantizationParams().getZeroPoint();
    }

    Map<String, Float> describe(Bitmap bitmap) {
        Bitmap scaled = bitmap.getWidth() == SIZE && bitmap.getHeight() == SIZE ? bitmap
                : Bitmap.createScaledBitmap(bitmap, SIZE, SIZE, true);
        scaled.getPixels(mPixels, 0, SIZE, 0, 0, SIZE, SIZE);
        mInput.rewind();
        for (int pixel : mPixels) {
            mInput.put((byte) (pixel >> 16));
            mInput.put((byte) (pixel >> 8));
            mInput.put((byte) pixel);
        }
        mInput.rewind();
        mInterpreter.run(mInput, mOutput);
        // The model ends in logits, not probabilities, so softmax them before any threshold
        // means anything. Indexed by ImageNet class id; output 0 is the background class.
        float[] probs = new float[mOutput[0].length - 1];
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < probs.length; i++) {
            probs[i] = ((mOutput[0][i + 1] & 0xff) - mZeroPoint) * mScale;
            max = Math.max(max, probs[i]);
        }
        float total = 0;
        for (int i = 0; i < probs.length; i++) {
            probs[i] = (float) Math.exp(probs[i] - max);
            total += probs[i];
        }
        for (int i = 0; i < probs.length; i++) {
            probs[i] /= total;
        }

        Map<String, Float> terms = new HashMap<>();
        float[] ranked = probs.clone();
        for (int picked = 0; picked < MAX_LABELS; picked++) {
            int best = -1;
            for (int i = 0; i < ranked.length; i++) {
                if (ranked[i] >= MIN_CONFIDENCE && (best < 0 || ranked[i] > ranked[best])) {
                    best = i;
                }
            }
            if (best < 0) {
                break;
            }
            for (String word : mLabels[best + 1].toLowerCase(Locale.ROOT).split("[^a-z]+")) {
                // "Maltese dog" must not make a kitten a dog; kinds come from the groups below.
                if (word.length() > 2 && !isAnimalWord(word)) {
                    put(terms, word, ranked[best]);
                }
            }
            ranked[best] = 0;
        }

        Map<String, Float> animals = groupSums(ANIMALS, probs);
        String kind = null;
        for (Map.Entry<String, Float> e : animals.entrySet()) {
            if (kind == null || e.getValue() > animals.get(kind)) {
                kind = e.getKey();
            }
        }
        if (kind != null && animals.get(kind) >= MIN_GROUP_CONFIDENCE) {
            addGroupWords(terms, ANIMALS, kind, animals.get(kind));
        }
        Map<String, Float> scenes = groupSums(SCENES, probs);
        for (Map.Entry<String, Float> e : scenes.entrySet()) {
            if (e.getValue() >= MIN_GROUP_CONFIDENCE) {
                addGroupWords(terms, SCENES, e.getKey(), e.getValue());
            }
        }
        for (Object[] extra : EXTRA_WORDS) {
            float sum = 0;
            for (int id : (int[]) extra[0]) {
                sum += probs[id];
            }
            if (sum >= MIN_GROUP_CONFIDENCE) {
                for (int i = 1; i < extra.length; i++) {
                    put(terms, (String) extra[i], sum);
                }
            }
        }
        return terms;
    }

    private static boolean isAnimalWord(String word) {
        for (Object[] row : ANIMALS) {
            for (int i = 2; i < row.length; i++) {
                if (row[i].equals(word)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Total probability per group, keyed by the group's first word. */
    private static Map<String, Float> groupSums(Object[][] table, float[] probs) {
        Map<String, Float> sums = new HashMap<>();
        for (Object[] row : table) {
            float sum = 0;
            for (int i = (int) row[0]; i <= (int) row[1]; i++) {
                sum += probs[i];
            }
            sums.merge((String) row[2], sum, Float::sum);
        }
        return sums;
    }

    private static void addGroupWords(Map<String, Float> terms, Object[][] table, String group,
            float score) {
        for (Object[] row : table) {
            if (row[2].equals(group)) {
                for (int i = 2; i < row.length; i++) {
                    put(terms, (String) row[i], score);
                }
            }
        }
    }

    private static void put(Map<String, Float> terms, String word, float score) {
        terms.merge(word, Math.min(1f, score), Math::max);
    }

    @Override
    public void close() {
        mInterpreter.close();
    }
}
