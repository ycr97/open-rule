package io.openrule.core.executor;

import io.openrule.core.context.DecisionContext;
import io.openrule.core.definition.NodeDefinition;
import io.openrule.core.definition.defs.OperatorDef;
import io.openrule.core.enums.NodeType;
import io.openrule.core.exception.FlowValidationException;
import io.openrule.core.exception.RuleEngineException;
import io.openrule.core.result.NodeResult;
import io.openrule.core.spi.CompiledNode;
import io.openrule.core.spi.NodeExecutor;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 内置运算符执行器：零编码字段比较。 */
public class OperatorNodeExecutor implements NodeExecutor {

    private static final int MAX_REGEX_LEN = 512;
    private static final Set<String> SUPPORTED = Set.of(
            "GT", "GTE", "LT", "LTE", "EQ", "NE", "BETWEEN",
            "CONTAINS", "NOT_CONTAINS", "STARTS_WITH", "ENDS_WITH",
            "IN", "NOT_IN", "IS_NULL", "NOT_NULL", "REGEX");

    @Override
    public NodeType supportType() { return NodeType.OPERATOR; }

    @Override
    public void validate(NodeDefinition node) throws FlowValidationException {
        OperatorDef def = node.getOperatorDef();
        if (def == null || def.getLeftFact() == null || def.getOperator() == null) {
            throw new FlowValidationException("OPERATOR 节点配置不完整: " + node.getNodeId());
        }
        if (!SUPPORTED.contains(def.getOperator())) {
            throw new FlowValidationException("不支持的运算符 " + def.getOperator()
                    + " @ " + node.getNodeId());
        }
    }

    @Override
    public NodeResult execute(DecisionContext ctx, CompiledNode compiled) {
        NodeDefinition node = compiled.getDefinition();
        OperatorDef def = node.getOperatorDef();
        long start = System.currentTimeMillis();

        Object leftValue = resolveValue(def.getLeftFact(), ctx);
        boolean hit = compare(leftValue, def.getOperator(), def.getRightValue());

        return NodeResult.builder()
                .nodeId(node.getNodeId())
                .nodeName(node.getNodeName())
                .nodeType(NodeType.OPERATOR)
                .hit(hit).success(true)
                .reason(hit ? buildHitReason(def, leftValue) : null)
                .details(Map.of(
                        "leftValue", String.valueOf(leftValue),
                        "operator", String.valueOf(def.getOperator()),
                        "rightValue", String.valueOf(def.getRightValue())))
                .costMillis(System.currentTimeMillis() - start)
                .build();
    }

    /** "fact.x.y" → facts 点路径；"var.k" → variables；其余视为字面量。 */
    private Object resolveValue(String ref, DecisionContext ctx) {
        if (ref == null) return null;
        if (ref.startsWith("fact.")) return ctx.getFacts().getByPath(ref.substring(5));
        if (ref.startsWith("var."))  return ctx.variable(ref.substring(4));
        return ref;
    }

    private String buildHitReason(OperatorDef def, Object leftValue) {
        return def.getLeftFact() + "(" + leftValue + ") " + def.getOperator()
                + " " + def.getRightValue();
    }

    private boolean compare(Object left, String op, Object right) {
        return switch (op) {
            case "GT"  -> toBigDecimal(left).compareTo(toBigDecimal(right)) > 0;
            case "GTE" -> toBigDecimal(left).compareTo(toBigDecimal(right)) >= 0;
            case "LT"  -> toBigDecimal(left).compareTo(toBigDecimal(right)) < 0;
            case "LTE" -> toBigDecimal(left).compareTo(toBigDecimal(right)) <= 0;
            case "EQ"  -> equalsLoose(left, right);
            case "NE"  -> !equalsLoose(left, right);
            case "BETWEEN" -> between(left, right);
            case "CONTAINS"     -> String.valueOf(left).contains(String.valueOf(right));
            case "NOT_CONTAINS" -> !String.valueOf(left).contains(String.valueOf(right));
            case "STARTS_WITH"  -> String.valueOf(left).startsWith(String.valueOf(right));
            case "ENDS_WITH"    -> String.valueOf(left).endsWith(String.valueOf(right));
            case "IN"     -> toCollection(right).stream().anyMatch(o -> equalsLoose(o, left));
            case "NOT_IN" -> toCollection(right).stream().noneMatch(o -> equalsLoose(o, left));
            case "IS_NULL"  -> left == null;
            case "NOT_NULL" -> left != null;
            case "REGEX" -> left != null
                    && compileRegex(String.valueOf(right)).matcher(String.valueOf(left)).matches();
            default -> throw new RuleEngineException("Unsupported operator: " + op);
        };
    }

    private boolean equalsLoose(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number && b instanceof Number) {
            return toBigDecimal(a).compareTo(toBigDecimal(b)) == 0;
        }
        return a.toString().equals(b.toString());
    }

    private boolean between(Object left, Object right) {
        List<?> bounds = toCollection(right).stream().toList();
        if (bounds.size() != 2) {
            throw new RuleEngineException("BETWEEN 需要 [lo, hi] 两个边界");
        }
        BigDecimal l = toBigDecimal(left);
        return l.compareTo(toBigDecimal(bounds.get(0))) >= 0
                && l.compareTo(toBigDecimal(bounds.get(1))) <= 0;
    }

    private static BigDecimal toBigDecimal(Object v) {
        if (v == null) throw new RuleEngineException("数值运算遇到 null");
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        return new BigDecimal(v.toString());
    }

    private static Collection<?> toCollection(Object v) {
        if (v instanceof Collection<?> c) return c;
        if (v instanceof Object[] a) return Arrays.asList(a);
        throw new RuleEngineException("IN/BETWEEN 期望集合类型，实际: " + v);
    }

    private static Pattern compileRegex(String pattern) {
        if (pattern.length() > MAX_REGEX_LEN) {
            throw new RuleEngineException("正则过长(>512)，疑似 ReDoS: len=" + pattern.length());
        }
        return Pattern.compile(pattern);
    }
}
