package eu.modernmt.decoder.neural;

import eu.modernmt.config.DecoderConfig;
import eu.modernmt.config.NeuralDecoderConfig;
import eu.modernmt.data.DataListener;
import eu.modernmt.data.DataListenerProvider;
import eu.modernmt.decoder.Decoder;
import eu.modernmt.decoder.DecoderException;
import eu.modernmt.decoder.DecoderListener;
import eu.modernmt.decoder.DecoderWithNBest;
import eu.modernmt.decoder.neural.execution.DecoderQueue;
import eu.modernmt.decoder.neural.memory.AlignmentDataFilter;
import eu.modernmt.decoder.neural.memory.ScoreEntry;
import eu.modernmt.decoder.neural.memory.TranslationMemory;
import eu.modernmt.decoder.neural.memory.lucene.LuceneTranslationMemory;
import eu.modernmt.decoder.neural.natv.NativeProcess;
import eu.modernmt.io.FileConst;
import eu.modernmt.io.Paths;
import eu.modernmt.io.TokensOutputStream;
import eu.modernmt.lang.LanguageIndex;
import eu.modernmt.lang.LanguagePair;
import eu.modernmt.lang.UnsupportedLanguageException;
import eu.modernmt.model.ContextVector;
import eu.modernmt.model.Sentence;
import eu.modernmt.model.Translation;
import eu.modernmt.model.Word;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Created by davide on 22/05/17.
 */
public class NeuralDecoder extends Decoder implements DecoderWithNBest, DataListenerProvider {

    private final Logger logger = LogManager.getLogger(getClass());

    private final int suggestionsLimit;
    private final TranslationMemory memory;
    private final Set<LanguagePair> directions;
    private final DecoderQueue decoderImpl;

    public NeuralDecoder(File model, DecoderConfig _config) throws DecoderException {
        super(model, _config);

        NeuralDecoderConfig config = (NeuralDecoderConfig) _config;

        // Load ModelConfig
        ModelConfig modelConfig;
        try {
            modelConfig = this.loadModelConfig(new File(model, "model.conf"));
        } catch (IOException e) {
            throw new NeuralDecoderException("Failed to read file model.conf", e);
        }

        // Translation Memory
        TranslationMemory memory;
        try {
            memory = loadTranslationMemory(modelConfig, new File(model, "memory"));
        } catch (IOException e) {
            throw new NeuralDecoderException("Failed to initialize memory", e);
        }

        // Decoder Queue
        DecoderQueue queue = loadDecoderQueue(modelConfig, config, model);

        // Init class fields
        this.suggestionsLimit = modelConfig.getSuggestionsLimit();
        this.memory = memory;
        this.directions = modelConfig.getAvailableTranslationDirections();
        this.decoderImpl = queue;
    }

    protected ModelConfig loadModelConfig(File filepath) throws IOException {
        return ModelConfig.load(filepath);
    }

    protected TranslationMemory loadTranslationMemory(ModelConfig config, File model) throws IOException {
        LanguageIndex languages = new LanguageIndex(config.getAvailableTranslationDirections());
        int queryMinResults = config.getQueryMinimumResults();

        LuceneTranslationMemory memory = new LuceneTranslationMemory(languages, model, queryMinResults);

        Map<LanguagePair, Float> thresholds = config.getAlignmentThresholds();
        if (thresholds != null && !thresholds.isEmpty())
            memory.setDataFilter(new AlignmentDataFilter(thresholds));

        return memory;
    }

    protected DecoderQueue loadDecoderQueue(ModelConfig modelConfig, NeuralDecoderConfig decoderConfig, File model) throws NeuralDecoderException {
        File pythonExec = Paths.join(FileConst.getLibPath(), "pynmt", "main_loop.py");
        NativeProcess.Builder builder = new NativeProcess.Builder(pythonExec, model);

        if (decoderConfig.isUsingGPUs())
            return DecoderQueue.newGPUInstance(builder, decoderConfig.getGPUs());
        else
            return DecoderQueue.newCPUInstance(builder, decoderConfig.getThreads());
    }

    // Decoder

    @Override
    public void setListener(DecoderListener listener) {
        listener.onTranslationDirectionsChanged(directions);
    }

    @Override
    public Translation translate(LanguagePair direction, String variant, Sentence text) throws NeuralDecoderException {
        return translate(direction, variant, text, null, 0);
    }

    @Override
    public Translation translate(LanguagePair direction, String variant, Sentence text, int nbestListSize) throws NeuralDecoderException {
        return translate(direction, variant, text, null, nbestListSize);
    }

    @Override
    public Translation translate(LanguagePair direction, String variant, Sentence text, ContextVector contextVector) throws NeuralDecoderException {
        return translate(direction, variant, text, contextVector, 0);
    }

    @Override
    public Translation translate(LanguagePair direction, String variant, Sentence text, ContextVector contextVector, int nbestListSize) throws NeuralDecoderException {
        if (!this.directions.contains(direction))
            throw new UnsupportedLanguageException(direction);

        Translation translation;

        if (text.hasWords()) {
            ScoreEntry[] suggestions;

            try {
                suggestions = memory.search(direction, text, contextVector, this.suggestionsLimit);
            } catch (IOException e) {
                throw new NeuralDecoderException("Failed to retrieve suggestions from memory", e);
            }

            if (suggestions != null && suggestions.length > 0) {
                // if perfect match, return suggestion instead
                if (suggestions[0].score == 1.f) {
                    Word[] words = new Word[suggestions[0].translation.length];
                    for (int i = 0; i < words.length; i++)
                        words[i] = new Word(suggestions[0].translation[i], " ");

                    translation = new Translation(words, text, null);
                } else {
                    translation = decoderImpl.translate(direction, variant, text, suggestions, nbestListSize);
                }
            } else {
                translation = decoderImpl.translate(direction, variant, text, nbestListSize);
            }

            if (logger.isTraceEnabled()) {
                String sourceText = TokensOutputStream.serialize(text, false, true);
                String targetText = TokensOutputStream.serialize(translation, false, true);

                StringBuilder log = new StringBuilder("Translation received from neural decoder:\n" +
                        "   sentence = " + sourceText + "\n" +
                        "   translation = " + targetText + "\n" +
                        "   suggestions = [\n");

                if (suggestions != null && suggestions.length > 0) {
                    for (ScoreEntry entry : suggestions)
                        log.append("      ").append(entry).append('\n');
                }

                log.append("   ]");

                logger.trace(log);
            }
        } else {
            translation = Translation.emptyTranslation(text);
        }

        return translation;
    }

    // DataListenerProvider

    @Override
    public Collection<DataListener> getDataListeners() {
        return Collections.singleton(memory);
    }

    // Closeable

    @Override
    public void close() {
        IOUtils.closeQuietly(this.decoderImpl);
        IOUtils.closeQuietly(this.memory);
    }

    @Override
    public boolean supportsSentenceSplit() {
        return true;
    }
}
