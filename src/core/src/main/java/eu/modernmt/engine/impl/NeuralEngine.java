package eu.modernmt.engine.impl;

import eu.modernmt.config.DecoderConfig;
import eu.modernmt.config.EngineConfig;
import eu.modernmt.decoder.Decoder;
import eu.modernmt.decoder.opennmt.OpenNMTDecoder;
import eu.modernmt.engine.Engine;
import eu.modernmt.engine.FileConst;
import eu.modernmt.io.Paths;
import eu.modernmt.persistence.PersistenceException;
import org.apache.commons.io.IOUtils;

import java.io.IOException;

/**
 * Created by davide on 22/05/17.
 */
public class NeuralEngine extends Engine {

    private final OpenNMTDecoder decoder;

    public NeuralEngine(EngineConfig config) throws IOException, PersistenceException {
        super(config);

        DecoderConfig decoderConfig = config.getDecoderConfig();
        if (decoderConfig.isEnabled())
            this.decoder = new OpenNMTDecoder(FileConst.getLibPath(), Paths.join(this.models, "decoder"));
        else
            this.decoder = null;
    }

    @Override
    public Decoder getDecoder() {
        if (decoder == null)
            throw new UnsupportedOperationException("Decoder unavailable");

        return decoder;
    }

    @Override
    public void close() {
        IOUtils.closeQuietly(decoder);
        super.close();
    }

}
