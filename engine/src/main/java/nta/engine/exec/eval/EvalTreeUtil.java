package nta.engine.exec.eval;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import nta.catalog.Column;
import nta.catalog.Schema;
import nta.catalog.proto.CatalogProtos.DataType;
import nta.engine.exception.InternalException;
import nta.engine.exec.eval.EvalNode.Type;
import nta.engine.parser.QueryBlock.Target;

import com.google.common.collect.Maps;
import com.google.common.collect.Sets;

/**
 * @author Hyunsik Choi
 */
public class EvalTreeUtil {
  public static void changeColumnRef(EvalNode node, Column oldName, 
      Column newName) {
    node.postOrder(new ChangeColumnRefVisitor(oldName.getQualifiedName(), 
        newName.getQualifiedName()));
  }
  
  public static void changeColumnRef(EvalNode node, String oldName, 
      String newName) {
    node.postOrder(new ChangeColumnRefVisitor(oldName, newName));
  }
  
  public static Set<Column> findDistinctRefColumns(EvalNode node) {
    DistinctColumnRefFinder finder = new DistinctColumnRefFinder();
    node.postOrder(finder);
    return finder.getColumnRefs();
  }
  
  public static List<Column> findAllColumnRefs(EvalNode node) {
    AllColumnRefFinder finder = new AllColumnRefFinder();
    node.postOrder(finder);
    return finder.getColumnRefs();
  }
  
  /**
   * Convert a list of conjunctive normal forms into a singleton expression.
   *  
   * @param evalNode
   * @return
   */
  public static EvalNode transformCNF2Singleton(EvalNode...evalNode) {    
    if (evalNode.length == 1) {
      return evalNode[0];
    }
    
    return transformCNF2Singleton_(evalNode, 0);
  }
  
  private static EvalNode transformCNF2Singleton_(EvalNode [] evalNode, int idx) {
    if (idx == evalNode.length - 2) {
      return new BinaryEval(Type.AND, evalNode[idx], evalNode[idx + 1]);
    } else {
      return new BinaryEval(Type.AND, evalNode[idx], 
          transformCNF2Singleton_(evalNode, idx + 1));
    }
  }
  
  /**
   * Get a list of exprs similar to CNF
   * 
   * @param node
   * @return
   */
  public static EvalNode [] getConjNormalForm(EvalNode node) {
    List<EvalNode> list = new ArrayList<EvalNode>();    
    getConjNormalForm(node, list);    
    return list.toArray(new EvalNode[list.size()]);
  }
  
  private static void getConjNormalForm(EvalNode node, List<EvalNode> found) {
    if (node.getType() == Type.AND) {
      getConjNormalForm(node.getLeftExpr(), found);
      getConjNormalForm(node.getRightExpr(), found);
    } else {
      found.add(node);
    }
  }
  
  /**
   * Compute a schema from a list of exprs.
   * 
   * @param inputSchema
   * @param evalNodes
   * @return
   * @throws InternalException
   */
  public static Schema getSchemaByExprs(Schema inputSchema, EvalNode [] evalNodes) 
      throws InternalException {
    Schema schema = new Schema();
    for (EvalNode expr : evalNodes) {
      schema.addColumn(
          expr.getName(),
          getDomainByExpr(inputSchema, expr));
    }
    
    return schema;
  }
  
  public static Schema getSchemaByTargets(Schema inputSchema, Target [] targets) 
      throws InternalException {
    Schema schema = new Schema();
    for (Target target : targets) {
      schema.addColumn(
          target.hasAlias() ? target.getAlias() : target.getEvalTree().getName(),
          getDomainByExpr(inputSchema, target.getEvalTree()));
    }
    
    return schema;
  }
  
  public static DataType getDomainByExpr(Schema inputSchema, EvalNode expr) 
      throws InternalException {
    switch (expr.getType()) {
    case AND:      
    case OR:
    case EQUAL:
    case NOT_EQUAL:
    case LTH:
    case LEQ:
    case GTH:
    case GEQ:
    case PLUS:
    case MINUS:
    case MULTIPLY:
    case DIVIDE:
    case CONST:
    case FUNCTION:
      return expr.getValueType();
      
    case FIELD:
      FieldEval fieldEval = (FieldEval) expr;
      return inputSchema.getColumn(fieldEval.getName()).getDataType();
      
    default:
      throw new InternalException("Unknown expr type: " 
          + expr.getType().toString());
    }
  }
  
  /**
   * Return all exprs to refer columns corresponding to the target.
   * 
   * @param expr 
   * @param target to be found
   * @return a list of exprs
   */
  public static Collection<EvalNode> getContainExpr(EvalNode expr, Column target) {
    Set<EvalNode> exprSet = Sets.newHashSet();    
    getContainExpr(expr, target, exprSet);
    return exprSet;
  }
  
  /**
   * Return the counter to count the number of expression types individually.
   *  
   * @param expr
   * @return
   */
  public static Map<Type, Integer> getExprCounters(EvalNode expr) {
    VariableCounter counter = new VariableCounter();
    expr.postOrder(counter);
    return counter.getCounter();
  }
  
  private static void getContainExpr(EvalNode expr, Column target, Set<EvalNode> exprSet) {
    switch (expr.getType()) {
    case EQUAL:
    case LTH:
    case LEQ:
    case GTH:
    case GEQ:
    case NOT_EQUAL:
      if (containColumnRef(expr, target)) {          
        exprSet.add(expr);
      }
    }    
  }
  
  /**
   * Examine if the expr contains the column reference corresponding 
   * to the target column
   * 
   * @param expr
   * @param target
   * @return
   */
  public static boolean containColumnRef(EvalNode expr, Column target) {
    Set<EvalNode> exprSet = Sets.newHashSet();
    _containColumnRef(expr, target, exprSet);
    
    return exprSet.size() > 0;
  }
  
  private static void _containColumnRef(EvalNode expr, Column target, 
      Set<EvalNode> exprSet) {
    switch (expr.getType()) {
    case FIELD:
      FieldEval field = (FieldEval) expr;
      if (field.getColumnName().equals(target.getColumnName())) {
        exprSet.add(field);
      }
      break;
    case CONST:
      return;
    default: 
      _containColumnRef(expr.getLeftExpr(), target, exprSet);
      _containColumnRef(expr.getRightExpr(), target, exprSet);
    }    
  }

  public static boolean isComparisonOperator(EvalNode expr) {
    return expr.getType() == Type.EQUAL ||
        expr.getType() == Type.LEQ ||
        expr.getType() == Type.LTH ||
        expr.getType() == Type.GEQ ||
        expr.getType() == Type.GTH;
  }

  public static boolean isLogicalOperator(EvalNode expr) {
    return expr.getType() == Type.AND || expr.getType() == Type.OR;
  }
  
  public static class ChangeColumnRefVisitor implements EvalNodeVisitor {    
    private final String findColumn;
    private final String toBeChanged;
    
    public ChangeColumnRefVisitor(String oldName, String newName) {
      this.findColumn = oldName;
      this.toBeChanged = newName;
    }
    
    @Override
    public void visit(EvalNode node) {
      if (node.type == Type.FIELD) {
        FieldEval field = (FieldEval) node;
        if (field.getColumnName().equals(findColumn)
            || field.getName().equals(findColumn)) {
          field.replaceColumnRef(toBeChanged);
        }
      }
    }    
  }
  
  public static class AllColumnRefFinder implements EvalNodeVisitor {
    private List<Column> colList = new ArrayList<Column>();
    private FieldEval field = null;
    
    @Override
    public void visit(EvalNode node) {
      if (node.getType() == Type.FIELD) {
        field = (FieldEval) node;
        colList.add(field.getColumnRef());
      } 
    }
    
    public List<Column> getColumnRefs() {
      return this.colList;
    }
  }
  
  public static class DistinctColumnRefFinder implements EvalNodeVisitor {
    private Set<Column> colList = new HashSet<Column>(); 
    private FieldEval field = null;
    
    @Override
    public void visit(EvalNode node) {
      if (node.getType() == Type.FIELD) {
        field = (FieldEval) node;
        colList.add(field.getColumnRef());
      }
    }
    
    public Set<Column> getColumnRefs() {
      return this.colList;
    }
  }
  
  public static class VariableCounter implements EvalNodeVisitor {
    private final Map<EvalNode.Type, Integer> counter;
    
    public VariableCounter() {
      counter = Maps.newHashMap();
      counter.put(Type.FUNCTION, 0);
      counter.put(Type.FIELD, 0);      
    }
    
    @Override
    public void visit(EvalNode node) {
      if (counter.containsKey(node.getType())) {
        int val = counter.get(node.getType());
        val++;
        counter.put(node.getType(), val);
      }
    }
    
    public Map<EvalNode.Type, Integer> getCounter() {
      return counter;
    }
  }
}