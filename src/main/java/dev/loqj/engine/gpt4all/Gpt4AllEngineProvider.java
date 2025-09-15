package dev.loqj.engine.gpt4all;

import dev.loqj.core.Config;
import dev.loqj.spi.*;

public final class Gpt4AllEngineProvider implements ModelEngineProvider {
    @Override public String id() { return "gpt4all"; }
    @Override public ModelEngine create(Config cfg) { return new Gpt4AllEngine(); }
    @Override public ModelCatalog catalog(Config cfg) { return new Gpt4AllCatalog(); }
}
