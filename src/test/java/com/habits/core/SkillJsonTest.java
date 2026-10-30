package com.habits.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every shipped skill JSON decodes through {@link SkillDef#CODEC} (what the loader does). Catches catalog typos at build time. */
class SkillJsonTest {
    @Test
    void allSkillFilesDecode() throws IOException {
        Path dir = Path.of(System.getProperty("habits.skillsDir", "src/main/resources/data/habits/habits_skills"));
        List<String> errors = new ArrayList<>();
        int n;
        try (Stream<Path> files = Files.walk(dir)) {
            List<Path> list = files.filter(f -> f.toString().endsWith(".json")).toList();
            n = list.size();
            for (Path f : list) {
                JsonObject o = JsonParser.parseString(Files.readString(f)).getAsJsonObject();
                o.addProperty("id", "habits:" + dir.relativize(f).toString().replace('\\', '/').replace(".json", ""));
                SkillDef.CODEC.parse(JsonOps.INSTANCE, o).error().ifPresent(e -> errors.add(f.getFileName() + ": " + e.message()));
            }
        }
        assertTrue(n >= 3, "found " + n + " skill files");
        assertTrue(errors.isEmpty(), String.join("\n", errors));
    }

    @Test
    void perkNeedsExactlyTypeOrMechanic() {
        JsonObject both = JsonParser.parseString("{\"type\":\"reach\",\"mechanic\":\"habits:x\",\"base\":1}").getAsJsonObject();
        JsonObject neither = JsonParser.parseString("{\"base\":1}").getAsJsonObject();
        assertTrue(PerkSpec.CODEC.parse(JsonOps.INSTANCE, both).isError());
        assertTrue(PerkSpec.CODEC.parse(JsonOps.INSTANCE, neither).isError());
    }
}
