package com.finance.platform.common.utils;

import com.finance.platform.common.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * 文件上传安全校验工具
 * <p>
 * 防 WebShell / 恶意文件上传：
 * <ul>
 *   <li>扩展名白名单校验</li>
 *   <li>Content-Type 校验</li>
 *   <li>魔数（Magic Number）校验：读取文件前 8 字节判断真实类型，避免扩展名伪造</li>
 *   <li>文件大小校验（由 Spring multipart 配置统一限制，此处仅做扩展名校验）</li>
 * </ul>
 * <p>
 * 支持的合法文件类型：
 * <ul>
 *   <li>.xlsx — Office Open XML（ZIP 容器，魔数 50 4B 03 04）</li>
 *   <li>.xls  — OLE2 复合文档（魔数 D0 CF 11 E0 A1 B1 1A E1）</li>
 *   <li>.csv  — 纯文本（无固定魔数，校验为可打印字符或 UTF-8 BOM）</li>
 * </ul>
 */
@Slf4j
public final class FileValidationUtils {

    private FileValidationUtils() {}

    /** 允许的扩展名（小写，无点） */
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("xlsx", "xls", "csv");

    /** 各扩展名对应的合法 Content-Type（宽松匹配，部分浏览器上传 csv 时可能用 application/octet-stream） */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", // .xlsx
            "application/vnd.ms-excel",                                            // .xls
            "text/csv",
            "application/csv",
            "text/plain",
            "application/octet-stream"                                             // 兜底：浏览器有时不分类型，靠魔数兜底
    );

    /** XLSX (ZIP) 魔数：50 4B 03 04 */
    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04};
    /** XLS (OLE2) 魔数：D0 CF 11 E0 A1 B1 1A E1 */
    private static final byte[] OLE2_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0,
            (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    /** UTF-8 BOM：EF BB BF */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** 最大允许文件大小：50MB（与 application.yml multipart.max-file-size 一致） */
    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024;

    /**
     * 校验上传的文件是否为合法的 Excel/CSV 文件
     * <p>
     * 校验顺序：非空 → 大小 → 扩展名 → Content-Type → 魔数。
     * 任一不通过抛出 BusinessException，包含具体原因与建议。
     *
     * @param file 上传文件
     * @return 文件扩展名（小写，无点），用于后续选择解析器
     */
    public static String validateExcelOrCsv(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件为空，请选择文件后再上传");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("文件大小超过 50MB 限制（当前 "
                    + formatSize(file.getSize()) + "），请拆分后再上传");
        }

        // 1. 扩展名校验
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new BusinessException("文件名为空，请检查上传文件");
        }
        String ext = extractExtension(originalFilename);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new BusinessException("不支持的文件类型：" + ext
                    + "，仅支持 .xlsx / .xls / .csv，请将文件另存为这些格式后上传");
        }

        // 2. Content-Type 校验（宽松，部分浏览器 csv 用 octet-stream）
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()
                && !ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new BusinessException("文件 Content-Type 不允许：" + contentType
                    + "，请上传 Excel(.xlsx/.xls) 或 CSV 文件");
        }

        // 3. 魔数校验（防扩展名伪造，如把 .exe 改成 .xlsx）
        try {
            byte[] head = readHead(file, 8);
            boolean magicOk;
            if ("xlsx".equals(ext)) {
                magicOk = startsWith(head, ZIP_MAGIC);
            } else if ("xls".equals(ext)) {
                magicOk = startsWith(head, OLE2_MAGIC);
            } else {
                // CSV：检查是否为可打印 ASCII 或 UTF-8 BOM
                magicOk = isPrintableOrUtf8(head);
            }
            if (!magicOk) {
                throw new BusinessException("文件内容与扩展名 ." + ext
                        + " 不匹配（魔数校验失败），疑似伪造文件类型；"
                        + "请用 Excel/WPS 另存为正确格式后重新上传");
            }
        } catch (IOException e) {
            throw new BusinessException("读取文件头失败：" + e.getMessage());
        }

        log.debug("[FileValidation] 文件校验通过 name={}, size={}, ext={}",
                originalFilename, file.getSize(), ext);
        return ext;
    }

    /** 提取扩展名（小写，无点）；无扩展名返回空串 */
    private static String extractExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase();
    }

    /** 读取文件前 n 字节 */
    private static byte[] readHead(MultipartFile file, int n) throws IOException {
        try (InputStream is = file.getInputStream()) {
            byte[] buf = new byte[n];
            int read = is.read(buf);
            if (read < buf.length) {
                byte[] exact = new byte[read];
                System.arraycopy(buf, 0, exact, 0, read);
                return exact;
            }
            return buf;
        }
    }

    /** 判断 buf 是否以 magic 开头 */
    private static boolean startsWith(byte[] buf, byte[] magic) {
        if (buf.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) {
            if (buf[i] != magic[i]) return false;
        }
        return true;
    }

    /**
     * CSV 文件头判定（文本文件无固定魔数，采用排除法）：
     * - UTF-8 BOM 开头 → 合法
     * - 否则检查前 8 字节中是否包含二进制控制字符：
     *   允许 0x09(Tab) / 0x0A(LF) / 0x0D(CR) / 0x20-0x7E(ASCII 可打印) / 0x80-0xFF(UTF-8 多字节字符，含中文)
     *   排除 0x00-0x08 / 0x0B / 0x0C / 0x0E-0x1F（二进制控制字符，正常 CSV 不会出现）
     */
    private static boolean isPrintableOrUtf8(byte[] head) {
        if (head.length == 0) return false;
        if (startsWith(head, UTF8_BOM)) return true;
        for (byte b : head) {
            int v = b & 0xFF;
            // 二进制控制字符区段：NUL(0x00)~BS(0x08)、VT(0x0B)、FF(0x0C)、SO(0x0E)~US(0x1F)
            if (v <= 0x08 || v == 0x0B || v == 0x0C || (v >= 0x0E && v <= 0x1F)) {
                return false;
            }
        }
        return true;
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + "B";
        if (bytes < 1024 * 1024) return String.format("%.1fKB", bytes / 1024.0);
        return String.format("%.1fMB", bytes / (1024.0 * 1024));
    }
}
