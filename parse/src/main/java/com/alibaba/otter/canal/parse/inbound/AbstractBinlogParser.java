package com.alibaba.otter.canal.parse.inbound;

import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import com.alibaba.druid.DbType;
import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.alibaba.druid.util.JdbcConstants;
import com.alibaba.otter.canal.common.AbstractCanalLifeCycle;
import com.alibaba.otter.canal.filter.aviater.AviaterRegexFilter;
import com.alibaba.otter.canal.parse.exception.CanalParseException;
import com.alibaba.otter.canal.protocol.CanalEntry;
import com.google.protobuf.ByteString;

/**
 * 实现BinlogParser接口的抽象类
 *
 * @param <T> Binlog的原始数据类型
 * @author wanghe Date: 2021/9/8 Time: 16:16
 */
public abstract class AbstractBinlogParser<T> extends AbstractCanalLifeCycle implements BinlogParser<T> {

    public static final Logger logger = LoggerFactory.getLogger(AbstractBinlogParser.class);

    /**
     * 字符集
     */
    protected Charset charset = Charset.defaultCharset();

    /**
     * 表过滤规则。运行时引用可能会有变化，比如规则发生变化时
     */
    protected volatile AviaterRegexFilter nameFilter;
    protected volatile AviaterRegexFilter nameBlackFilter;

    /**
     * 字段过滤规则
     */
    protected Map<String, List<String>> fieldFilterMap      = new HashMap<>();
    protected Map<String, List<String>> fieldBlackFilterMap = new HashMap<>();

    /**
     * 类型过滤
     */
    protected boolean filterQueryDcl = false;
    protected boolean filterQueryDml = false;
    protected boolean filterQueryDdl = false;

    @Override
    public void reset() {
    }

    @Override
    public void stop() {
        reset();
        super.stop();
    }

    /**
     * 补全SQL中的schema
     *
     * @param sql    原始的SQL语句
     * @param schema 库名
     * @param table  表名
     * @return 补全后的SQL
     */
    protected String completeTableNameInSql(String sql, String schema, String table) {
        if (StringUtils.isBlank(sql) || StringUtils.isBlank(schema) || StringUtils.isBlank(table)) {
            return sql;
        }
        DbType dbType = JdbcConstants.MYSQL;
        try {
            List<SQLStatement> stmtList = SQLUtils.parseStatements(sql, dbType);
            if (CollectionUtils.isEmpty(stmtList)) {
                return sql;
            }

            for (SQLStatement sqlStatement : stmtList) {
                TableNameModifier modifier = new TableNameModifier(schema, table);
                sqlStatement.accept(modifier);
                return sqlStatement.toString();
            }
        } catch (Exception e) {
            logger.error("set schema to sql err: {}", e.getMessage());
        }
        return sql;
    }

    /**
     * 一个补全表名的SQLASTVisitor实现
     */
    public class TableNameModifier extends MySqlASTVisitorAdapter {

        private final String schema;
        private final String table;

        public TableNameModifier(String schema, String table) {
            this.schema = schema;
            this.table = table;
        }

        @Override
        public boolean visit(SQLExprTableSource x) {
            x.setExpr(structureSchema(table));
            x.setSchema(structureSchema(schema));
            x.getExpr().accept(this);
            return false;
        }
    }

    /**
     * 补全schema中的反引号
     *
     * @param schema 原schema
     * @return 处理后的schema
     */
    protected String structureSchema(String schema) {
        if (schema.startsWith("`") && schema.endsWith("`")) {
            return schema;
        }
        return "`" + schema + "`";
    }

    /**
     * 字段过滤判断
     *
     * @param fieldList      列名白名单
     * @param blackFieldList 列名黑名单
     * @param columnName     字段的列名
     * @return 是否保留该字段
     */
    protected boolean needField(List<String> fieldList, List<String> blackFieldList, String columnName) {
        if (fieldList == null || fieldList.isEmpty()) {
            return blackFieldList == null || blackFieldList.isEmpty()
                   || !blackFieldList.contains(columnName.toUpperCase());
        } else {
            return fieldList.contains(columnName.toUpperCase());
        }
    }

    protected CanalEntry.Entry createEntry(CanalEntry.Header header, CanalEntry.EntryType entryType,
                                           ByteString storeValue) {
        CanalEntry.Entry.Builder entryBuilder = CanalEntry.Entry.newBuilder();
        entryBuilder.setHeader(header);
        entryBuilder.setEntryType(entryType);
        entryBuilder.setStoreValue(storeValue);
        return entryBuilder.build();
    }

    protected CanalEntry.TransactionBegin createTransactionBegin(long threadId) {
        CanalEntry.TransactionBegin.Builder beginBuilder = CanalEntry.TransactionBegin.newBuilder();
        beginBuilder.setThreadId(threadId);
        return beginBuilder.build();
    }

    protected CanalEntry.TransactionEnd createTransactionEnd(long transactionId) {
        CanalEntry.TransactionEnd.Builder endBuilder = CanalEntry.TransactionEnd.newBuilder();
        endBuilder.setTransactionId(String.valueOf(transactionId));
        return endBuilder.build();
    }

    protected CanalEntry.Pair createSpecialPair(String key, String value) {
        CanalEntry.Pair.Builder pairBuilder = CanalEntry.Pair.newBuilder();
        pairBuilder.setKey(key);
        pairBuilder.setValue(value);
        return pairBuilder.build();
    }

    protected boolean isUpdate(List<CanalEntry.Column> bfColumns, String newValue, int index) {
        if (bfColumns == null) {
            throw new CanalParseException("ERROR ## the bfColumns is null");
        }

        if (index < 0) {
            return false;
        }

        for (CanalEntry.Column column : bfColumns) {
            // 比较before / after的column index
            if (column.getIndex() == index) {
                if (column.getIsNull() && newValue == null) {
                    // 如果全是null
                    return false;
                } else if (newValue != null && (!column.getIsNull() && column.getValue().equals(newValue))) {
                    // fixed issue #135, old column is Null
                    // 如果不为null，并且相等
                    return false;
                }
            }
        }

        // 比如nolob/minial模式下,可能找不到before记录,认为是有变化
        return true;
    }

    public void setCharset(Charset charset) {
        this.charset = charset;
    }

    public void setNameFilter(AviaterRegexFilter nameFilter) {
        this.nameFilter = nameFilter;
        logger.warn("--> init table filter : " + nameFilter.toString());
    }

    public void setNameBlackFilter(AviaterRegexFilter nameBlackFilter) {
        this.nameBlackFilter = nameBlackFilter;
        logger.warn("--> init table black filter : " + nameBlackFilter.toString());
    }

    public void setFieldFilterMap(Map<String, List<String>> fieldFilterMap) {
        if (fieldFilterMap != null) {
            this.fieldFilterMap = fieldFilterMap;
        } else {
            this.fieldFilterMap = new HashMap<>();
        }

        for (Map.Entry<String, List<String>> entry : this.fieldFilterMap.entrySet()) {
            logger.warn("--> init field filter : " + entry.getKey() + "->" + entry.getValue());
        }
    }

    public void setFieldBlackFilterMap(Map<String, List<String>> fieldBlackFilterMap) {
        if (fieldBlackFilterMap != null) {
            this.fieldBlackFilterMap = fieldBlackFilterMap;
        } else {
            this.fieldBlackFilterMap = new HashMap<>();
        }

        for (Map.Entry<String, List<String>> entry : this.fieldBlackFilterMap.entrySet()) {
            logger.warn("--> init field black filter : " + entry.getKey() + "->" + entry.getValue());
        }
    }

    public void setFilterQueryDcl(boolean filterQueryDcl) {
        this.filterQueryDcl = filterQueryDcl;
    }

    public void setFilterQueryDml(boolean filterQueryDml) {
        this.filterQueryDml = filterQueryDml;
    }

    public void setFilterQueryDdl(boolean filterQueryDdl) {
        this.filterQueryDdl = filterQueryDdl;
    }
}
