package com.acme.prism.core.parser;

import com.acme.prism.common.enums.AnyFile;
import com.acme.prism.core.parser.converter.*;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * JSON转任何文件
 *
 * @author 拒绝者
 * @date 2025-01-26
 * @see AnyFile
 */
public class JsonParser {
    /**
     * 转换器
     */
    private static final Map<AnyFile, DataFormatConverter> CONVERTERS = createConverters();

    /**
     * 转换器
     *
     * @param json         数据
     * @param targetFormat 目标格式
     * @return {@link String }
     */
    public static String convert(final String json, final AnyFile targetFormat) {
        return getConverter(targetFormat).convert(json);
    }

    /**
     * 反向转换
     *
     * @param json         数据
     * @param targetFormat 目标格式
     * @return {@link String }
     */
    public static String reverseConvert(final String json, final AnyFile targetFormat) {
        if (Objects.isNull(targetFormat)) {
            return "";
        }
        return getConverter(targetFormat).reverseConvert(json);
    }

    private static Map<AnyFile, DataFormatConverter> createConverters() {
        final EnumMap<AnyFile, DataFormatConverter> converters = new EnumMap<>(AnyFile.class);
        register(converters, AnyFile.XML, new XmlConverter());
        register(converters, AnyFile.CSV, new CsvConverter());
        register(converters, AnyFile.YAML, new YamlConverter());
        register(converters, AnyFile.TOML, new TomlConverter());
        register(converters, AnyFile.XLSX, new XlsxConverter());
        register(converters, AnyFile.CLASS, new ClassConverter());
        register(converters, AnyFile.RECORD, new RecordConverter());
        register(converters, AnyFile.BASE64, new Base64Converter());
        register(converters, AnyFile.URL_PARAMS, new UrlParamsConverter());
        register(converters, AnyFile.PROPERTIES, new PropertiesConverter());
        register(converters, AnyFile.SQL, new SqlConverter());
        register(converters, AnyFile.MARKDOWN, new MarkdownTableConverter());
        register(converters, AnyFile.TYPESCRIPT, new TypeScriptConverter());
        return Map.copyOf(converters);
    }

    private static void register(final EnumMap<AnyFile, DataFormatConverter> converters,
                                 final AnyFile targetFormat,
                                 final DataFormatConverter converter) {
        converters.put(targetFormat, converter);
    }

    private static DataFormatConverter getConverter(final AnyFile targetFormat) {
        final DataFormatConverter converter = CONVERTERS.get(targetFormat);
        if (Objects.isNull(converter)) {
            throw new IllegalArgumentException("不支持的格式");
        }
        return converter;
    }
}
