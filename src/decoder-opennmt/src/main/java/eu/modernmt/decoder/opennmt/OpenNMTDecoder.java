package eu.modernmt.decoder.opennmt;

import eu.modernmt.decoder.Decoder;
import eu.modernmt.decoder.DecoderFeature;
import eu.modernmt.decoder.DecoderTranslation;
import eu.modernmt.decoder.opennmt.model.TranslationRequest;
import eu.modernmt.model.ContextVector;
import eu.modernmt.model.Sentence;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * Created by davide on 22/05/17.
 */
public class OpenNMTDecoder implements Decoder {

    private final ExecutionQueue executor;

    public OpenNMTDecoder(File libPath, File modelPath) throws IOException {
        File pythonHome = new File(libPath, "opennmt");

        ProcessBuilder builder = new ProcessBuilder("python", "nmt_decoder.py", modelPath.getAbsolutePath());
        builder.directory(pythonHome);

        this.executor = new ExecutionQueue(builder.start());
    }

    // Decoder

    @Override
    public DecoderFeature[] getFeatures() {
        throw new UnsupportedOperationException("Decoder features not supported by Neural Decoder");
    }

    @Override
    public float[] getFeatureWeights(DecoderFeature feature) {
        throw new UnsupportedOperationException("Decoder features not supported by Neural Decoder");
    }

    @Override
    public void setDefaultFeatureWeights(Map<DecoderFeature, float[]> weights) {
        throw new UnsupportedOperationException("Decoder features not supported by Neural Decoder");
    }

    @Override
    public DecoderTranslation translate(Sentence text) throws OpenNMTException {
        return translate(text, null, 0);
    }

    @Override
    public DecoderTranslation translate(Sentence text, ContextVector contextVector) throws OpenNMTException {
        return translate(text, contextVector, 0);
    }

    @Override
    public DecoderTranslation translate(Sentence text, int nbestListSize) throws OpenNMTException {
        return translate(text, null, nbestListSize);
    }

    @Override
    public DecoderTranslation translate(Sentence text, ContextVector contextVector, int nbestListSize) throws OpenNMTException {
        if (nbestListSize > 0)
            throw new UnsupportedOperationException("N-Best not supported by current Neural Decoder implementation");

        TranslationRequest request = new TranslationRequest(text);
        return executor.execute(request).get();
    }

    // Closeable

    @Override
    public void close() throws IOException {
        this.executor.close();
    }

}
