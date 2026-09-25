# 树形聚合的增量更新组件

Pair-wise GSB 标注任务仓库（第 13 批 / 170）。

| 项目 | 内容 |
|------|------|
| 任务类型 | Feature 迭代 |
| 任务难度 | 困难 |
| 语言/框架 | Java, Maven, JUnit 5 |
| 环境可复现等级 | 无外部依赖 |
| 构建方式 | Maven（含 mvnw wrapper，无需本机安装 Maven） |

> 本仓库是**初始环境快照**：只有工程骨架，不含任何实现代码。
> 分支说明：`main` 为初始环境；`A`、`B` 为两次独立执行各自的工作分支，均从 `main` 的同一个提交拉出。

## 运行方式

```bash
./mvnw -q verify
```

## 任务提示词

以下为本题完整的 User Prompt 原文，两次执行必须使用完全相同的文本。

我们要按组织层级统计指标，明细数据频繁变化，每次全量重算整棵树的代价太高。请从零实现一个树形聚合的增量更新组件。仓库目前只有一个空的 Maven 工程（pom.xml 只声明 JUnit 5 与 AssertJ）。要求：1) 支持注册树形层级（父子关系）与叶子节点的指标值；2) 支持叶子值更新时只沿路径更新祖先节点，避免全量重算，需给出更新代价说明；3) 支持任意节点查询聚合值（求和、最大值、计数至少三种）；4) 支持节点结构调整（把子树挂到新父节点），调整后所有受影响祖先的值要正确更新；5) 支持子树删除与新增，删除后父节点聚合值必须同步下调；6) 提供统计：节点总数、最近一次更新的受影响节点数与全量重算的对比；7) 测试覆盖增量更新正确性、多种聚合、结构变更、子树增删与更新代价对比；`mvn -q verify` 一条命令跑通。

## 提交要求

1. 在本仓库中完成提示词要求的全部内容。
2. `./mvnw -q verify` 必须通过。
3. 完成后在所属分支（A 或 B）上提交，产物快照的父提交必须是初始环境快照。

---

## 实现说明（分支 B）

### 组件结构

| 类 | 职责 |
|----|------|
| `com.example.gsb.rollup.RollupTree` | 核心组件：层级注册、叶子赋值、增量聚合、子树挂载/删除、统计 |
| `com.example.gsb.rollup.Aggregate` | 聚合结果快照：`sum` / `max` / `count` |
| `com.example.gsb.rollup.RollupStats` | 统计：节点总数、最近变更的增量代价、全量重算代价、节省比例 |

### 核心 API

```java
RollupTree tree = new RollupTree();
tree.addNode("root", null);          // 注册层级（parentId 为 null 表示根）
tree.addNode("deptA", "root");
tree.addNode("leaf1", "deptA");
tree.setValue("leaf1", 42.0);        // 叶子赋值，只沿路径更新祖先
Aggregate agg = tree.aggregate("deptA"); // sum / max / count
tree.move("deptA", "otherParent");   // 子树挂到新父节点
tree.removeSubtree("deptA");         // 删除子树，父链同步下调
RollupStats stats = tree.stats();    // 增量 vs 全量代价对比
```

### 更新代价

- **叶子值更新**：`O(h · k)`，`h` 为叶子到根的路径长度，`k` 为路径上节点的最大子节点数。每个祖先由其直接子节点的缓存值重算，路径之外的节点完全不被访问。
- **子树挂载/删除**：`O(h · k)`（删除另加 `O(s)` 摘除登记，`s` 为子树大小）。子树内部缓存原样保留，无需重算。
- **全量重算对比**：`O(N)`，`N` 为全树节点数。`RollupStats.savingsRatio()` 给出增量相对全量节省的节点访问比例（测试示例：40 节点的树更新一个深叶只访问 4 个节点，节省 90%）。
