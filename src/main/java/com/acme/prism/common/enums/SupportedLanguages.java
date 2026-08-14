package com.acme.prism.common.enums;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.ide.highlighter.XmlFileType;
import com.intellij.json.JsonFileType;
import com.intellij.lang.properties.PropertiesFileType;
import com.intellij.openapi.fileTypes.LanguageFileType;
import com.intellij.openapi.fileTypes.PlainTextFileType;
import org.jetbrains.yaml.YAMLFileType;
import org.toml.lang.psi.TomlFileType;

import java.util.EnumMap;
import java.util.Map;

/**
 * 支持语言
 *
 * @author 拒绝者
 * @date 2025-04-22
 */
public enum SupportedLanguages {
    YAML(YAMLFileType.YML, AnyFile.YAML),
    XML(XmlFileType.INSTANCE, AnyFile.XML),
    TOML(TomlFileType.INSTANCE, AnyFile.TOML),
    JSON(JsonFileType.INSTANCE, AnyFile.JSON),
    CLASS(JavaFileType.INSTANCE, AnyFile.CLASS),
    CSV(PlainTextFileType.INSTANCE, AnyFile.CSV),
    RECORD(JavaFileType.INSTANCE, AnyFile.RECORD),
    TEXT(PlainTextFileType.INSTANCE, AnyFile.TEXT),
    BASE64(PlainTextFileType.INSTANCE, AnyFile.BASE64),
    URL_PARAMS(PlainTextFileType.INSTANCE, AnyFile.URL_PARAMS),
    SQL(PlainTextFileType.INSTANCE, AnyFile.SQL),
    PROPERTIES(PropertiesFileType.INSTANCE, AnyFile.PROPERTIES),
    MARKDOWN(PlainTextFileType.INSTANCE, AnyFile.MARKDOWN),
    TYPESCRIPT(PlainTextFileType.INSTANCE, AnyFile.TYPESCRIPT);
    private static final Map<AnyFile, SupportedLanguages> BY_ANY_FILE = createIndex();
    private final AnyFile extension;
    private final LanguageFileType fileType;

    SupportedLanguages(final LanguageFileType fileType, final AnyFile extension) {
        this.fileType = fileType;
        this.extension = extension;
    }

    /**
     * 通过任何文件获取
     *
     * @param any 任何
     * @return {@link SupportedLanguages }
     */
    public static SupportedLanguages getByAnyFile(final AnyFile any) {
        return BY_ANY_FILE.getOrDefault(any, SupportedLanguages.TEXT);
    }

    private static Map<AnyFile, SupportedLanguages> createIndex() {
        final EnumMap<AnyFile, SupportedLanguages> index = new EnumMap<>(AnyFile.class);
        for (final SupportedLanguages language : values()) {
            index.put(language.extension, language);
        }
        return Map.copyOf(index);
    }

    /**
     * 获取文件类型
     *
     * @return {@link T }
     */
    @SuppressWarnings("unchecked")
    public <T extends LanguageFileType> T getFileType() {
        return (T) this.fileType;
    }

    /**
     * 获得拓展
     *
     * @return {@link AnyFile }
     */
    @SuppressWarnings("unused")
    public AnyFile getExtension() {
        return this.extension;
    }
}
