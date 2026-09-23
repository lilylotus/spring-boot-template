package org.example.simple.util.excel;

import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;

import org.xml.sax.XMLReader;

/**
 * 安全 XML 解析器工厂，统一禁用 DTD 和外部实体，避免解析不可信 XLSX 时发生外部实体攻击。
 */
final class ExcelXmlSupport {

    /** 静态工具类不允许实例化。 */
    private ExcelXmlSupport() {
    }

    /**
     * 创建启用安全处理特性的命名空间感知 SAX 读取器。
     *
     * @return 已禁用 DTD 和外部实体的 SAX 读取器
     * @throws ExcelProcessingException 当前 JVM 无法提供所需安全特性时抛出
     */
    static XMLReader newReader() {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            return factory.newSAXParser().getXMLReader();
        } catch (Exception exception) {
            throw new ExcelProcessingException(ExcelErrorType.CONFIGURATION, "初始化安全 XML 解析器失败", exception);
        }
    }
}
