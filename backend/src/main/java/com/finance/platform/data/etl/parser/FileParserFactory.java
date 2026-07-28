package com.finance.platform.data.etl.parser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 文件解析器工厂
 * <p>
 * 根据 ImportTemplate.fileType 选择对应的 FileParser 实现。
 * 同时支持按文件扩展名自动识别解析器类型。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FileParserFactory {

    private final List<FileParser> parsers;
    private Map<String, FileParser> parserMap;

    @org.springframework.beans.factory.annotation.Autowired
    public void init() {
        parserMap = parsers.stream()
                .collect(Collectors.toMap(FileParser::supportedType, Function.identity()));
        log.info("[FileParserFactory] 已注册解析器：{}", parserMap.keySet());
    }

    /**
     * 根据文件类型标识获取解析器
     */
    public FileParser getParser(String fileType) {
        FileParser parser = parserMap.get(fileType.toUpperCase());
        if (parser == null) {
            throw new IllegalArgumentException("不支持的文件类型：" + fileType + "，已注册：" + parserMap.keySet());
        }
        return parser;
    }

    /**
     * 根据文件名扩展名自动识别解析器
     */
    public FileParser getParserByFileName(String fileName) {
        if (fileName == null) {
            return getParser("EXCEL");
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".csv")) {
            return getParser("CSV");
        }
        return getParser("EXCEL");
    }
}
