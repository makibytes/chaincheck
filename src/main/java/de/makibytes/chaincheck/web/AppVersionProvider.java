/*
 * Copyright (c) 2026 MakiBytes.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 */
package de.makibytes.chaincheck.web;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.springframework.stereotype.Component;

@Component
public class AppVersionProvider {

    private static final String POM_PROPERTIES_PATH = "META-INF/maven/de.makibytes/chaincheck/pom.properties";
    private static final String POM_PATH = "pom.xml";
    private static final String ARTIFACT_ID_MARKER = "<artifactId>chaincheck</artifactId>";
    private static final String VERSION_OPEN = "<version>";
    private static final String VERSION_CLOSE = "</version>";

    private final String version;

    public AppVersionProvider() {
        this.version = resolveVersion();
    }

    public String getVersion() {
        return version;
    }

    private String resolveVersion() {
        String fromProperties = loadFromPomProperties();
        if (fromProperties != null && !fromProperties.isBlank()) {
            return fromProperties.trim();
        }
        String fromPom = loadFromPomXml();
        if (fromPom != null && !fromPom.isBlank()) {
            return fromPom.trim();
        }
        return "unknown";
    }

    private String loadFromPomProperties() {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(POM_PROPERTIES_PATH)) {
            if (input == null) {
                return null;
            }
            Properties props = new Properties();
            props.load(input);
            return props.getProperty("version");
        } catch (IOException ex) {
            return null;
        }
    }

    private String loadFromPomXml() {
        Path path = Path.of(POM_PATH);
        if (!Files.exists(path)) {
            return null;
        }
        try {
            String content = Files.readString(path);
            int markerIndex = content.indexOf(ARTIFACT_ID_MARKER);
            if (markerIndex < 0) {
                return null;
            }
            int versionStart = content.indexOf(VERSION_OPEN, markerIndex);
            if (versionStart < 0) {
                return null;
            }
            int versionEnd = content.indexOf(VERSION_CLOSE, versionStart);
            if (versionEnd < 0) {
                return null;
            }
            int valueStart = versionStart + VERSION_OPEN.length();
            return content.substring(valueStart, versionEnd).trim();
        } catch (IOException ex) {
            return null;
        }
    }
}
