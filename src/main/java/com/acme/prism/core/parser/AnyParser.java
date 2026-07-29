package com.acme.prism.core.parser;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.lang.Opt;
import cn.hutool.core.util.StrUtil;
import com.acme.prism.common.enums.AnyFile;
import com.alibaba.fastjson2.JSON;
import org.jetbrains.annotations.NotNull;
import org.xml.sax.InputSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.toml.TomlMapper;
import tools.jackson.dataformat.yaml.YAMLMapper;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.List;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 通用格式自动识别与反向转换。
 *
 * @author 拒绝者
 * @date 2025-05-05
 * @see AnyFile
 */
@SuppressWarnings({"RegExpRedundantClassElement", "RegExpSimplifiable"})
public class AnyParser {
    private static final TomlMapper TOML_MAPPER = new TomlMapper();
    private static final YAMLMapper YAML_MAPPER = YAMLMapper.builder().build();
    private static final Pattern YAML_LIST_PATTERN = Pattern.compile("(?m)^\\s*-\\s+.+$");
    private static final Pattern BASE64_PATTERN = Pattern.compile("^[A-Za-z0-9+/\\r\\n]+=*$");
    private static final Pattern WINDOWS_PATH_PATTERN = Pattern.compile("^[a-zA-Z]:[\\\\/].*$");
    private static final Pattern URI_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z\\d+.-]*://.+$");
    private static final Pattern YAML_DOCUMENT_PATTERN = Pattern.compile("(?m)^\\s*(?:---|\\.\\.\\.)\\s*$");
    private static final Pattern PROPERTIES_PATTERN = Pattern.compile("^(?!\\s*(?:#|$)).+?=.+$", Pattern.MULTILINE);
    private static final Pattern YAML_MAPPING_PATTERN = Pattern.compile("(?m)^\\s*[^\\s:#][^\\r\\n]*:\\s*(?:[^\\r\\n]*)$");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final ThreadLocal<DocumentBuilderFactory> XML_FACTORY = ThreadLocal.withInitial(AnyParser::createXmlFactory);
    /**
     * 无识别价值的空白 JSON 样本集合（去除全部空白字符后进行匹配）
     */
    private static final Set<String> SKIPPABLE_SAMPLES = Set.of(
            "{", "{}", "[", "[]", "[\"]", "{\"}", "{\"\"}", "{\"\":}", "{\"\":\"}", "{\"\":\"\"}", "{\"\":\"\",}"
    );
    /**
     * Base64 文本的最小有效长度
     */
    private static final int MIN_BASE64_LENGTH = 8;
    /**
     * 禁止 DOCTYPE 声明的 XML 特性
     */
    private static final String FEATURE_DISALLOW_DOCTYPE = "https://apache.org/xml/features/disallow-doctype-decl";
    /**
     * 禁止外部通用实体的 XML 特性
     */
    private static final String FEATURE_EXTERNAL_GENERAL_ENTITIES = "https://xml.org/sax/features/external-general-entities";
    /**
     * 禁止外部参数实体的 XML 特性
     */
    private static final String FEATURE_EXTERNAL_PARAMETER_ENTITIES = "https://xml.org/sax/features/external-parameter-entities";
    private static final List<DetectRule> DETECT_RULES = List.of(
            new DetectRule(AnyFile.XML, AnyParser::looksLikeXml, AnyParser::isXml),
            new DetectRule(AnyFile.YAML, AnyParser::looksLikeYaml, AnyParser::isYaml),
            new DetectRule(AnyFile.TOML, AnyParser::looksLikeToml, AnyParser::isToml),
            new DetectRule(AnyFile.BASE64, AnyParser::looksLikeBase64, Base64::isBase64),
            new DetectRule(AnyFile.URL_PARAMS, AnyParser::looksLikeUrlParams, AnyParser::isUrlParams),
            new DetectRule(AnyFile.PROPERTIES, AnyParser::looksLikeProperties, AnyParser::isProperties)
    );

    /**
     * 自动识别文本格式并转换为 JSON。
     *
     * @param any 任意输入
     * @return JSON 结果；识别失败返回空串
     */
    public static String convert(final String any) {
        if (JSON.isValid(any)) {
            return "";
        }
        return Opt.ofBlankAble(any)
                .map(item -> JsonParser.reverseConvert(item, detectType(item)))
                .filter(StrUtil::isNotEmpty)
                .orElse("");
    }

    private static AnyFile detectType(final String input) {
        final String trimmed = StrUtil.emptyIfNull(input).trim();
        if (isSkippable(trimmed)) return null;
        for (final DetectRule rule : DETECT_RULES) {
            if (rule.quickMatcher().matches(trimmed) && rule.validator().matches(trimmed)) {
                return rule.type();
            }
        }
        return null;
    }

    private static boolean looksLikeXml(final String input) {
        return input.startsWith("<");
    }

    private static boolean looksLikeYaml(final String input) {
        if (URI_PATTERN.matcher(input).matches() || WINDOWS_PATH_PATTERN.matcher(input).matches()) {
            return Boolean.FALSE;
        }
        return YAML_DOCUMENT_PATTERN.matcher(input).find()
                || YAML_LIST_PATTERN.matcher(input).find()
                || YAML_MAPPING_PATTERN.matcher(input).find();
    }

    private static boolean looksLikeToml(final String input) {
        return (input.indexOf('=') >= 0 && input.indexOf('&') == -1) || input.startsWith("[");
    }

    private static boolean looksLikeBase64(final String input) {
        // 正斜杠开头多为路径（如 /api/data），排除非 Base64 场景
        return input.length() >= MIN_BASE64_LENGTH && input.charAt(0) != '/'
                && BASE64_PATTERN.matcher(input).matches();
    }

    private static boolean looksLikeUrlParams(final String input) {
        return input.indexOf('=') >= 0 || input.indexOf('&') >= 0;
    }

    private static boolean looksLikeProperties(final String input) {
        return PROPERTIES_PATTERN.matcher(input).find();
    }

    private static boolean isSkippable(@NotNull final String text) {
        return SKIPPABLE_SAMPLES.contains(WHITESPACE_PATTERN.matcher(StrUtil.emptyIfNull(text).trim()).replaceAll(""));
    }

    private static boolean isXml(final String input) {
        if (!looksLikeXml(input)) return Boolean.FALSE;
        try {
            XML_FACTORY.get().newDocumentBuilder().parse(new InputSource(new StringReader(input)));
            return Boolean.TRUE;
        } catch (final Exception e) {
            return Boolean.FALSE;
        }
    }

    /**
     * 判断是否为 URL 参数格式。
     *
     * @param input 输入文本
     * @return 是否为 URL 参数
     */
    public static boolean isUrlParams(final String input) {
        if (input.indexOf('=') == -1 && input.indexOf('&') == -1) {
            return Boolean.FALSE;
        }
        final String[] pairs = input.split("&", -1);
        boolean hasValidPair = Boolean.FALSE;
        for (final String pair : pairs) {
            if (StrUtil.isEmpty(pair)) continue;
            if (pair.indexOf('=') == -1) {
                hasValidPair = Boolean.TRUE;
                continue;
            }
            final String[] keyValue = pair.split("=", 2);
            if (keyValue.length > 0 && !StrUtil.isEmpty(keyValue[0])) {
                hasValidPair = Boolean.TRUE;
            }
        }
        return hasValidPair;
    }

    private static boolean isYaml(final String input) {
        try {
            final JsonNode result = YAML_MAPPER.readTree(input);
            return Objects.nonNull(result) && (result.isObject() || result.isArray());
        } catch (final Exception e) {
            return Boolean.FALSE;
        }
    }

    private static boolean isToml(final String input) {
        try {
            TOML_MAPPER.readTree(input);
            return Boolean.TRUE;
        } catch (final Exception e) {
            return Boolean.FALSE;
        }
    }

    private static boolean isProperties(final String input) {
        if (!PROPERTIES_PATTERN.matcher(input).find()) {
            return Boolean.FALSE;
        }
        try {
            new Properties().load(new StringReader(input));
            return Boolean.TRUE;
        } catch (final Exception e) {
            return Boolean.FALSE;
        }
    }

    private static DocumentBuilderFactory createXmlFactory() {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setXIncludeAware(Boolean.FALSE);
        factory.setExpandEntityReferences(Boolean.FALSE);
        setFeatureQuietly(factory, XMLConstants.FEATURE_SECURE_PROCESSING, Boolean.TRUE);
        setFeatureQuietly(factory, FEATURE_DISALLOW_DOCTYPE, Boolean.TRUE);
        setFeatureQuietly(factory, FEATURE_EXTERNAL_GENERAL_ENTITIES, Boolean.FALSE);
        setFeatureQuietly(factory, FEATURE_EXTERNAL_PARAMETER_ENTITIES, Boolean.FALSE);
        return factory;
    }

    private static void setFeatureQuietly(final DocumentBuilderFactory factory, final String feature, final boolean value) {
        try {
            factory.setFeature(feature, value);
        } catch (final Exception ignored) {
        }
    }

    @FunctionalInterface
    private interface TextMatcher {
        boolean matches(String input);
    }

    private record DetectRule(AnyFile type, TextMatcher quickMatcher, TextMatcher validator) {
    }
}
