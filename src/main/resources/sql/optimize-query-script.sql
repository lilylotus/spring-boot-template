-- ============================================================
-- batch_test 表在千万/亿级数据量下的分页查询优化方案汇总
-- ============================================================
-- 表结构见 db/migration/V2__create_batch_test_and_user_data_tables.sql：
--   CREATE TABLE batch_test (
--       id INT NOT NULL AUTO_INCREMENT PRIMARY KEY,
--       string_field1~10 VARCHAR(256) NOT NULL,
--       create_time/update_time DATETIME NOT NULL
--   )
--
-- 背景：MySQL对 LIMIT offset, rows 的默认实现是"先取offset+rows行、再丢弃前offset行"，
-- offset越大要扫描/丢弃的无效行越多，翻到后面的页码会越来越慢；数据量到千万/亿级后这个问题
-- 尤其明显。本文件汇总几种常见优化思路，都基于batch_test现有的主键索引，不需要改表结构
-- (方案五/六额外建了辅助表，是可选项)。
--
-- 说明：本文件是执行计划(EXPLAIN)对比参考脚本，请在真实拥有大数据量的环境执行才有分析意义；
-- 当前模板仓库自带的样例数据量很小，直接跑不会体现出性能差异，仅用于理解每种方案的写法和原理。
-- 部分已落地为Java代码的方案对应 BatchTestMapper（queryOptimal/queryOptimalJoin/
-- queryOptimalAssociate/queryOptimalPrimaryId），可以对照阅读 mybatis/mapper/BatchTestMapper.xml。
-- ============================================================


-- ===== 问题现象：传统 LIMIT offset, rows 分页 =====
-- 越往后翻页offset越大，MySQL要先定位到第offset+1行才能开始返回数据，即使offset部分走了
-- 主键索引，仍然需要逐行跳过并回表读取被丢弃的那offset行的完整数据，成本随offset线性增长。
EXPLAIN
SELECT id, string_field1, string_field2, string_field3, string_field4, string_field5,
       string_field6, string_field7, string_field8, string_field9, string_field10,
       create_time, update_time
FROM batch_test
ORDER BY id
LIMIT 80000000, 20;   -- 亿级数据下offset到八千万时，这条SQL可能要几十秒甚至更久


-- ===== 方案一：子查询延迟关联(Deferred Join)，先用覆盖索引定位起始id =====
-- 原理：子查询"SELECT id FROM batch_test ORDER BY id LIMIT offset,1"只需要扫描主键索引本身
-- (覆盖索引，不用回表读整行)，比直接LIMIT offset,rows全字段扫描快得多；拿到起始id后再用
-- WHERE id >= 起始id 做范围查询，只需要真正读取rows行的完整数据。
-- 对应 BatchTestMapper#queryOptimal(offset, rows)。
-- 优点：改造成本最低，只调整SQL不用动表结构；缺点：子查询本身仍要跳过offset行索引记录，
-- offset极大时依然会变慢，但比"问题现象"里的写法快得多。
EXPLAIN
SELECT id, string_field1, string_field2, string_field3, string_field4, string_field5,
       string_field6, string_field7, string_field8, string_field9, string_field10,
       create_time, update_time
FROM batch_test
WHERE id >= (
    SELECT id FROM batch_test ORDER BY id LIMIT 80000000, 1
)
ORDER BY id
LIMIT 20;


-- ===== 方案二：INNER JOIN 延迟关联，效果与方案一相同，只是写成JOIN而不是WHERE子查询 =====
-- 原理同方案一：先在覆盖索引上定位这一页的id集合，再JOIN回原表取完整数据；
-- 部分MySQL版本/优化器对JOIN形式选择的执行计划比WHERE子查询更稳定，实际项目里建议两种
-- 写法都EXPLAIN对比一下，选更优的那个。
-- 对应 BatchTestMapper#queryOptimalJoin(offset, rows)。
EXPLAIN
SELECT b.id, b.string_field1, b.string_field2, b.string_field3, b.string_field4, b.string_field5,
       b.string_field6, b.string_field7, b.string_field8, b.string_field9, b.string_field10,
       b.create_time, b.update_time
FROM ( SELECT id FROM batch_test ORDER BY id LIMIT 80000000, 20 ) a
INNER JOIN batch_test b ON a.id = b.id
ORDER BY b.id;


-- ===== 方案三：游标/Seek分页(Keyset Pagination)，彻底摆脱offset =====
-- 原理：分页时不再传offset，而是记住上一页最后一条记录的id作为"游标"，下一页直接
-- WHERE id > 上一页最后一个id ORDER BY id LIMIT rows；因为id是主键，这个WHERE条件能
-- 直接走索引定位起点，扫描行数只等于rows，与offset/翻到第几页完全无关，是亿级数据下
-- 分页性能最稳定的方案。
-- 对应 BatchTestMapper#queryOptimalPrimaryId(lastId, rows)。
-- 限制：只能"上一页/下一页"顺序翻页，不支持直接跳转到任意页码，前端需要改造成
-- "加载更多"/游标翻页交互，而不是"输入页码跳转"。
EXPLAIN
SELECT id, string_field1, string_field2, string_field3, string_field4, string_field5,
       string_field6, string_field7, string_field8, string_field9, string_field10,
       create_time, update_time
FROM batch_test
WHERE id > 80000000   -- 上一页最后一条记录的id；首页传0（或省略该条件）
ORDER BY id
LIMIT 20;


-- ===== 方案四：按id区间批量扫描，用于全量导出/离线批处理场景 =====
-- 原理与方案三同源，但用于后台导出/批处理而不是前端分页：按固定步长把id空间切成若干区间，
-- 应用代码循环处理每个区间，每次查询成本只取决于区间大小，与"处理到第几批"无关；
-- 不同区间之间没有依赖，天然可以并行处理，适合亿级数据的全量导出/离线加工任务。
EXPLAIN
SELECT id, string_field1, string_field2, string_field3, string_field4, string_field5,
       string_field6, string_field7, string_field8, string_field9, string_field10,
       create_time, update_time
FROM batch_test
WHERE id > 80000000 AND id <= 80001000   -- 区间步长按经验值调整(如1000~10000)，太大内存压力大，太小请求次数多
ORDER BY id;


-- ===== 方案五：分页锚点表(Bookmark Table)，支持亿级数据下的任意页码跳转 =====
-- 方案三/四放弃了"跳到第N页"的能力；如果产品要求必须支持任意页码跳转，可以预先算好
-- "第几页 -> 该页起始id"的映射存进一张很小的锚点表，查询任意页时先查锚点表拿到起始id
-- (锚点表本身很小，主键查询是O(1))，再用 WHERE id >= 起始id ORDER BY id LIMIT rows
-- 直接定位，不需要临时扫描/跳过前面的行。
-- 代价：数据有增删时锚点会偏移，需要定期重新生成，更适合相对静态的历史数据分页场景。
CREATE TABLE IF NOT EXISTS batch_test_page_anchor (
    page_no    INT NOT NULL,   -- 页码：每ANCHOR_STEP条记录一个锚点，页码=第几个锚点(从0开始)
    start_id   INT NOT NULL,   -- 该锚点对应的起始id
    PRIMARY KEY (page_no)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 生成锚点表示例：每1000条记录记一个锚点。用ROW_NUMBER()按id顺序编号，不依赖id本身连续
-- (AUTO_INCREMENT在有删除/事务回滚时会有空洞，不能直接用id % 1000判断)。
-- 亿级数据上首次生成这张表成本较高，建议在低峰期离线执行。
TRUNCATE TABLE batch_test_page_anchor;
INSERT INTO batch_test_page_anchor (page_no, start_id)
SELECT (rn - 1) DIV 1000 AS page_no, id AS start_id
FROM (
    SELECT id, ROW_NUMBER() OVER (ORDER BY id) AS rn
    FROM batch_test
) ranked
WHERE (rn - 1) % 1000 = 0;

-- 查询第N个锚点(第80000*1000条记录附近)，再从该起始id继续分页
EXPLAIN
SELECT start_id FROM batch_test_page_anchor WHERE page_no = 80000;


-- ===== 方案六：避免 COUNT(*) 全表扫描统计总数/总页数 =====
-- 分页UI通常还需要"共XX条/共XX页"，亿级数据上直接 SELECT COUNT(*) FROM batch_test 是
-- 全表扫描，跟上面几种分页优化同样重要，容易被忽略。

-- 6.1 用近似行数：MySQL基于统计信息估算，可能有误差，但是O(1)、几乎瞬时返回
SELECT TABLE_ROWS AS approximate_row_count
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'batch_test';

-- 6.2 如果必须要精确总数，考虑在应用层维护一张单行计数表，INSERT/DELETE batch_test的
-- 同一个事务里同步+1/-1（需要在Java代码里实现，此处只给出计数表结构示例）
CREATE TABLE IF NOT EXISTS batch_test_row_count (
    id         TINYINT NOT NULL DEFAULT 1,   -- 固定为1，全表只有一行，纯粹用来存当前总数
    row_count  BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;


-- ===== 方案七【推荐用于生产环境】：双向游标分页(Bidirectional Keyset Pagination) =====
-- 方案三的游标分页只给了"下一页"的写法，方案五的锚点表能任意跳页但需要额外维护、数据有
-- 增删时会偏移；生产环境更常见的诉求是"用户能自由点上一页/下一页，两个方向都要快、
-- 都不能随数据增长变慢，且不需要额外维护表"——这正是方案七要解决的问题：不传offset，
-- 只传"当前页第一条/最后一条记录的id"这个游标，翻页方向靠SQL里ORDER BY的方向控制：
--   下一页：WHERE id > 游标 ORDER BY id ASC  LIMIT pageSize
--   上一页：WHERE id < 游标 ORDER BY id DESC LIMIT pageSize（应用层拿到结果后再反转成升序展示）
-- 两个方向的WHERE条件都直接命中主键索引定位起点，扫描行数恒等于pageSize，跟翻到第几页、
-- 表里总共多少行完全无关，这也是它相比方案一/二(延迟关联)在超深分页时更快、
-- 相比方案五不需要额外建表/定期重建的原因。生产环境实践中额外建议：
--   1) 每次多查1行(LIMIT pageSize+1)，多出的第pageSize+1行只用来判断"是否还有下一页/上一页"，
--      不展示给用户，这样不用再单独发一次COUNT查询去判断"是不是最后一页"；
--   2) 首页(没有游标)时WHERE条件整体省略，直接 ORDER BY id ASC LIMIT pageSize；
--   3) 如果排序字段不是主键id而是可能重复的普通列(如create_time)，要用"排序列+id"的
--      联合游标(如 WHERE (create_time, id) > (:lastCreateTime, :lastId))并在该联合列上建
--      联合索引，否则排序列有重复值时会漏数据/重复数据——这是本方案能稳定支持"随意前后翻页"
--      的关键前提，务必保证游标使用的排序字段组合是唯一的(id本身唯一，天然满足)。

-- 下一页：从游标id=80000000之后取20条(pageSize=20)，多取1条(limit 21)用来判断是否还有下一页
EXPLAIN
SELECT id, string_field1, string_field2, string_field3, string_field4, string_field5,
       string_field6, string_field7, string_field8, string_field9, string_field10,
       create_time, update_time
FROM batch_test
WHERE id > 80000000
ORDER BY id ASC
LIMIT 21;

-- 上一页：从游标id=80000000之前取20条，同样多取1条判断是否还有上一页；
-- 结果是id倒序(80000000附近往小取)，应用层拿到结果后需要再反转一次顺序才是正常的升序展示
EXPLAIN
SELECT id, string_field1, string_field2, string_field3, string_field4, string_field5,
       string_field6, string_field7, string_field8, string_field9, string_field10,
       create_time, update_time
FROM batch_test
WHERE id < 80000000
ORDER BY id DESC
LIMIT 21;


-- ===== 方案对比小结 =====
-- 方案一/二(延迟关联)：改造成本最低，适合offset不算特别夸张(几百万以内)的场景，越往后越慢；
-- 方案三(游标/Seek分页)：只支持"下一页"，性能稳定，写法比方案七简单；
-- 方案四(id区间批扫描)：适合离线全量导出/批处理，天然可并行，不是面向前端分页交互的方案；
-- 方案五(锚点表)：能在亿级数据下支持"任意页码跳转"，但要接受额外维护成本、数据增删会偏移；
-- 方案六(近似/预聚合计数)：解决"总数/总页数"这个分页UI背后隐藏的全表扫描点，
--   通常要配合以上任一分页方案一起使用，不能单独解决分页慢的问题；
-- 方案七(双向游标分页)：生产环境推荐首选——上一页/下一页都快、与数据量和翻页深度无关、
--   不需要额外建表维护，代价是不支持"输入页码直接跳转"，前端交互只能是"上一页/下一页"，
--   如果产品硬性要求"跳转到第N页"，需要退回方案五(接受额外维护成本)或方案一/二(接受深度退化)。
