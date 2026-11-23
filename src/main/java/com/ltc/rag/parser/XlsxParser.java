package com.ltc.rag.parser;

import com.ltc.rag.common.ApiException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.Set;

/**
 * Excel → Markdown 表格（每个工作表一节）。
 *
 * <p>用 {@link WorkbookFactory} 而不是直接 new XSSFWorkbook：它按文件头自动识别
 * .xls(OLE2) 与 .xlsx(OXML)，顺便修掉"老 .xls 一解析就抛异常"的经典 bug。
 */
@Component
public class XlsxParser implements DocumentParser {

    private static final Set<String> EXT = Set.of("xlsx", "xls");
    /** 防呆：一个几万行的台账表不该整本进向量库，只会把检索淹掉。 */
    private static final int MAX_ROWS_PER_SHEET = 800;
    private static final int MAX_COLS = 30;

    @Override
    public Set<String> extensions() {
        return EXT;
    }

    @Override
    public Parsed parse(byte[] bytes, String fileName) {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter(Locale.CHINA);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            StringBuilder md = new StringBuilder();

            for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
                Sheet sheet = workbook.getSheetAt(i);
                if (sheet.getPhysicalNumberOfRows() == 0) {
                    continue;
                }
                md.append("## ").append(sheet.getSheetName()).append("\n\n");

                int emitted = 0;
                boolean headerDone = false;
                for (Row row : sheet) {
                    if (emitted >= MAX_ROWS_PER_SHEET) {
                        md.append("\n> 本表超过 ").append(MAX_ROWS_PER_SHEET).append(" 行，其余已截断\n");
                        break;
                    }
                    String line = renderRow(row, formatter, evaluator);
                    if (line == null) {
                        continue;
                    }
                    md.append(line).append('\n');
                    if (!headerDone) {
                        int columns = (int) line.chars().filter(ch -> ch == '|').count() - 1;
                        md.append('|').append(" --- |".repeat(Math.max(1, columns))).append('\n');
                        headerDone = true;
                    }
                    emitted++;
                }
                md.append('\n');
            }

            return new Parsed(md.toString().strip(), fileName.toLowerCase(Locale.ROOT).endsWith(".xls") ? "xls" : "xlsx");
        } catch (Exception e) {
            throw ApiException.badRequest("PARSE_XLSX_FAILED", "Excel 解析失败：" + e.getMessage());
        }
    }

    private static String renderRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row == null) {
            return null;
        }
        StringBuilder line = new StringBuilder("|");
        int cells = 0;
        boolean hasContent = false;
        for (int c = 0; c < Math.min(row.getLastCellNum(), MAX_COLS); c++) {
            Cell cell = row.getCell(c);
            String value = "";
            if (cell != null) {
                try {
                    value = formatter.formatCellValue(cell, evaluator);
                } catch (RuntimeException e) {
                    // 公式依赖外部数据源时 evaluator 会抛错，退化成显示公式原文
                    value = formatter.formatCellValue(cell);
                }
            }
            value = value == null ? "" : value.strip().replace("|", "\\|").replace("\n", " ");
            hasContent |= !value.isEmpty();
            line.append(' ').append(value).append(" |");
            cells++;
        }
        if (!hasContent || cells == 0) {
            return null;
        }
        return line.toString();
    }
}
