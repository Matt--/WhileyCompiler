package wycs.core;

import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import wycc.lang.Attribute;
import wycc.lang.NameID;
import wycc.lang.SyntacticElement;
import wycc.util.Pair;

/**
 * Represents a "bytecode" in the language of the Wycs theorem prover. Bytecodes
 * are recursively defined trees, where each node is an instance of
 * <code>Code</code> with the following attributes:
 * 
 * <ul>
 * <li><b>Type</b>. Provides information about the type of operands used in the
 * bytecode.</li>
 * <li><b>Opcode</b> Determines the exact operation described by the bytecode.</li>
 * <li><b>Operands</b> Identifies zero or more operands for this bytecode.</li>
 * </ul>
 * 
 * In addition, there are various subclasses of <code>Code</code> which encode
 * additional information (e.g. constant values, variable indices, etc).
 * 
 * @author David J. Pearce
 * 
 * @param <T>
 */
public abstract class Code<T extends SemanticType> extends SyntacticElement.Impl {
	
	/**
	 * The type associated with this bytecode. Note that this is not necessarily
	 * related to the type of the value that this expression evaluates to. To
	 * access the that type, the <code>returnType()</code> method should be used.
	 */
	public final T type;
	
	/**
	 * The opcode which defines what this bytecode does. Observe that certain
	 * bytecodes must correspond with specific subclasses. For example,
	 * <code>opcode == LOAD</code> always indicates that <code>this</code> is an
	 * instanceof <code>Load</code>.
	 */
	public final Op opcode;
	
	/**
	 * An array of zero of more code operands. The exact number of operands
	 * depends on the opcode in question. For example, an <code>Add</code>
	 * opcode indicates there are exactly <code>2</code> operands. On the
	 * otherhand, a
	 * <code>Tuple</copde> opcode indicates that there are zero or more operands.
	 */
	public final Code<?>[] operands;
	
	public Code(T type, Op opcode, Code<?>[] operands, Attribute... attributes) {
		super(attributes);
		this.type = type;
		this.opcode = opcode;
		this.operands = operands;		
	}
	
	public Code(T type, Op opcode, Code<?>[] operands, Collection<Attribute> attributes) {
		super(attributes);
		this.type = type;
		this.opcode = opcode;
		this.operands = operands;		
	}
	
	/**
	 * Substitute bytecodes for variables in this bytecode as determined by a
	 * given map. Variables which are not keys of the <code>binding</code> map
	 * are untouched.
	 * 
	 * @param binding
	 *            --- a map from variables to the bytecodes wwhich are to
	 *            replace them.
	 * @return
	 */
	public Code substitute(Map<Integer,Code> binding) {
		Code<?>[] nOperands = operands;
		for(int i=0;i!=nOperands.length;++i) {
			Code o = nOperands[i];
			Code c = o.substitute(binding);
			if(c != o && operands == nOperands) {
				nOperands = Arrays.copyOf(operands, operands.length);
			}
			nOperands[i] = c;
		}
		if(nOperands != operands) {
			return clone(type,opcode,nOperands);
		}
		return this;
	}
	
	/**
	 * Instantiate generic variables with concrete types in bytecodes as
	 * determined by a given map. Generic variables which are not keys of the
	 * <code>binding</code> map are untouched.
	 * 
	 * @param binding
	 *            --- a map from variables to the bytecodes wwhich are to
	 *            replace them.
	 * @return
	 */
	public Code instantiate(Map<String,SemanticType> binding) {
		// First, attempt to instantiate our type
		T nType = (T) type.substitute(binding);
		
		// Second, attempt to recursively instantiate generic variables in
		// operands  
		Code<?>[] nOperands = operands;
		for(int i=0;i!=nOperands.length;++i) {
			Code o = nOperands[i];
			Code c = o.instantiate(binding);
			if(c != o && operands == nOperands) {
				nOperands = Arrays.copyOf(operands, operands.length);
			}
			nOperands[i] = c;
		}
		
		if(nOperands != operands || nType != type) {
			return clone(nType,opcode,nOperands);
		}
		return this;
	}
	
	public abstract Code clone(T type, Op opcode, Code<?>[] operands);
	
	/**
	 * Determine the most precise type capturing any value that this bytecode
	 * could evaluate to.
	 * 
	 * @return
	 */
	public abstract SemanticType returnType();
	
	// ==================================================================
	// Constructors
	// ==================================================================
	
	public static Variable Variable(SemanticType type, int index, Attribute... attributes) {
		return new Variable(type,new Code[0],index,attributes);
	}
	
	public static Variable Variable(SemanticType type, int index, Collection<Attribute> attributes) {
		return new Variable(type,new Code[0],index,attributes);
	}
	
	public static Variable Variable(SemanticType type, Code<?>[] operands, int index, Attribute... attributes) {
		return new Variable(type,operands,index,attributes);
	}
	
	public static Variable Variable(SemanticType type, Code<?>[] operands, int index, Collection<Attribute> attributes) {
		return new Variable(type,operands,index,attributes);
	}
	
	
	public static Constant Constant(Value value, Attribute... attributes) {
		return new Constant(value,attributes);
	}
	
	public static Constant Constant(Value value, Collection<Attribute> attributes) {
		return new Constant(value,attributes);
	}
	
	public static Unary Unary(SemanticType type, Op opcode, Code<?> operand,
			Attribute... attributes) {
		return new Unary(type,opcode,operand,attributes);
	}
	
	public static Unary Unary(SemanticType type, Op opcode, Code<?> operand,
			Collection<Attribute> attributes) {
		return new Unary(type,opcode,operand,attributes);
	}
	
	public static Binary Binary(SemanticType type, Op opcode, Code<?> leftOperand,
			Code<?> rightOperand, Attribute... attributes) {
		return new Binary(type,opcode,leftOperand,rightOperand,attributes);
	}
	
	public static Binary Binary(SemanticType type, Op opcode, Code<?> leftOperand,
			Code<?> rightOperand, Collection<Attribute> attributes) {
		return new Binary(type,opcode,leftOperand,rightOperand,attributes);
	}
		
	public static Nary Nary(SemanticType type, Op opcode, Code<?>[] operands,
			Attribute... attributes) {
		return new Nary(type,opcode,operands,attributes);
	}
	
	public static Nary Nary(SemanticType type, Op opcode, Code<?>[] operands,
			Collection<Attribute> attributes) {
		return new Nary(type,opcode,operands,attributes);
	}
	
	public static Load Load(SemanticType.Tuple type, Code<?> source, int index,
			Attribute... attributes) {
		return new Load(type,source,index,attributes);
	}
	
	public static Load Load(SemanticType.Tuple type, Code<?> source, int index,
			Collection<Attribute> attributes) {
		return new Load(type,source,index,attributes);
	}
	
	public static Quantifier Quantifier(SemanticType type, Op opcode,
			Code<?> operand, Pair<SemanticType,Integer>[] types,
			Attribute... attributes) {
		return new Quantifier(type, opcode, operand, types, attributes);
	}
	
	public static Quantifier Quantifier(SemanticType type, Op opcode,
			Code<?> operand, Pair<SemanticType,Integer>[] types,
			Collection<Attribute> attributes) {
		return new Quantifier(type, opcode, operand, types, attributes);
	}
	
	public static FunCall FunCall(SemanticType.Function type, Code operand, NameID nid,
			Attribute... attributes) {
		return new FunCall(type,operand,nid,attributes);
	}
	
	public static FunCall FunCall(SemanticType.Function type, Code operand, NameID nid,
			Collection<Attribute> attributes) {
		return new FunCall(type,operand,nid,attributes);
	}
	
	// ==================================================================
	// Classes
	// ==================================================================
	
	public static enum Op {
		NULL(0),
		VAR(1),
		CONST(2),
		NOT(3),
		NEG(4),
		LENGTH(5),
		ADD(6),
		SUB(7),
		MUL(8),
		DIV(9),
		REM(10),
		EQ(11),
		NEQ(12),
		LT(13),
		LTEQ(14),
		IN(15),
		SUBSET(16),
		SUBSETEQ(17),
		AND(18),
		OR(19),
		TUPLE(20),
		SET(21),
		LOAD(22),
		EXISTS(23),
		FORALL(24),
		FUNCALL(25);
		
		public int offset;

		private Op(int offset) {
			this.offset = offset;
		}
	}
	
	private static Code[] NO_OPERANDS = new Code[0];
	
	public final static class Variable extends Code<SemanticType> {
		public final int index;
		
		private Variable(SemanticType type, Code<?>[] operands, int index,
				Attribute... attributes) {
			super(type, Op.VAR, operands, attributes);
			this.index = index;
		}

		private Variable(SemanticType type, Code<?>[] operands, int index,
				Collection<Attribute> attributes) {
			super(type, Op.VAR, operands, attributes);
			this.index = index;
		}
		
		@Override
		public Code substitute(Map<Integer,Code> binding) {
			Code r = binding.get(index);
			if(r != null) {
				return r;
			} else{
				return this;
			}
		}
		
		@Override
		public SemanticType returnType() {
			return type;
		}
		
		@Override
		public Code clone(SemanticType type, Op opcode, Code<?>[] operands) {
			return Variable(type,operands,index,attributes());
		}
	}
	
	public final static class Constant extends Code<SemanticType> {
		public final Value value;
		
		private Constant(Value value, Attribute... attributes) {
			super(value.type(),Op.CONST,NO_OPERANDS,attributes);			
			this.value = value;
		}
		
		private Constant(Value value, Collection<Attribute> attributes) {
			super(value.type(), Op.CONST, NO_OPERANDS, attributes);
			this.value = value;
		}
		
		@Override
		public SemanticType returnType() {
			return value.type();
		}
		
		@Override
		public Code clone(SemanticType type, Op opcode, Code<?>[] operands) {
			return this;
		}
	}
	
	public final static class Unary extends Code<SemanticType> {
		private Unary(SemanticType type, Op opcode, Code<?> operand,
				Attribute... attributes) {
			super(type, opcode, new Code[] { operand }, attributes);
			if (opcode.offset < Op.NOT.offset
					|| opcode.offset > Op.LENGTH.offset) {
				throw new IllegalArgumentException(
						"invalid opcode for Unary constructor");
			}
		}

		private Unary(SemanticType type, Op opcode, Code<?> operand,
				Collection<Attribute> attributes) {
			super(type, opcode, new Code[] { operand }, attributes);
			if (opcode.offset < Op.NOT.offset
					|| opcode.offset > Op.LENGTH.offset) {
				throw new IllegalArgumentException(
						"invalid opcode for Unary constructor");
			}
		}
		
		@Override
		public SemanticType returnType() {
			switch(opcode) {
			case NEG:
			case NOT:
				return type;
			case LENGTH:
				return SemanticType.Int;
			}
			throw new IllegalArgumentException("invalid opcode for unary bytecode");
		}
		
		@Override
		public Code clone(SemanticType type, Op opcode, Code<?>[] operands) {
			return Unary(type,opcode,operands[0],attributes());
		}
	}
	
	public final static class Binary extends Code<SemanticType> {
		private Binary(SemanticType type, Op opcode, Code<?> leftOperand,
				Code<?> rightOperand, Attribute... attributes) {
			super(type, opcode, new Code[] { leftOperand, rightOperand },
					attributes);
			if (opcode.offset < Op.ADD.offset
					|| opcode.offset > Op.SUBSETEQ.offset) {
				throw new IllegalArgumentException(
						"invalid opcode for Unary constructor");
			}
		}

		private Binary(SemanticType type, Op opcode, Code<?> leftOperand,
				Code rightOperand, Collection<Attribute> attributes) {
			super(type, opcode, new Code[] { leftOperand, rightOperand },
					attributes);
			if (opcode.offset < Op.ADD.offset
					|| opcode.offset > Op.SUBSETEQ.offset) {
				throw new IllegalArgumentException(
						"invalid opcode for Binary constructor");
			}
		}
		
		@Override
		public SemanticType returnType() {
			switch(opcode) {
			case ADD:
			case SUB:			
			case MUL:
			case DIV:
			case REM:
				return type;
			case EQ:
			case NEQ:			
			case LT:			
			case LTEQ:
			case IN:
			case SUBSET:				
			case SUBSETEQ:
				return SemanticType.Bool;	
			}
			throw new IllegalArgumentException("invalid opcode for binary bytecode");
		}
		
		@Override
		public Code clone(SemanticType type, Op opcode, Code<?>[] operands) {
			return Binary(type,opcode,operands[0],operands[1],attributes());
		}
	}
	
	public final static class Nary extends Code<SemanticType> {
		private Nary(SemanticType type, Op opcode, Code<?>[] operands,
				Attribute... attributes) {
			super(type, opcode, operands, attributes);
			if (opcode.offset < Op.AND.offset || opcode.offset > Op.SET.offset) {
				throw new IllegalArgumentException(
						"invalid opcode for Nary constructor");
			}
		}

		private Nary(SemanticType type, Op opcode, Code<?>[] operands,
				Collection<Attribute> attributes) {
			super(type, opcode, operands, attributes);
			if (opcode.offset < Op.AND.offset || opcode.offset > Op.SET.offset) {
				throw new IllegalArgumentException(
						"invalid opcode for Nary constructor");
			}
		}
		
		@Override
		public SemanticType returnType() {
			switch(opcode) {
			case AND:
			case OR:
				return SemanticType.Bool;		
			case SET:				
				return type;
			case TUPLE:
				return type;	
			}
			throw new IllegalArgumentException("invalid opcode for binary bytecode");
		}
				
		@Override
		public Code clone(SemanticType type, Op opcode, Code<?>[] operands) {
			return Nary(type,opcode,operands,attributes());
		}
	}
	
	public final static class Load extends Code<SemanticType.Tuple> {
		public final int index;

		private Load(SemanticType.Tuple type, Code<?> source, int index,
				Attribute... attributes) {
			super(type, Op.LOAD, new Code[] { source }, attributes);
			this.index = index;
		}

		private Load(SemanticType.Tuple type, Code<?> source, int index,
				Collection<Attribute> attributes) {
			super(type, Op.LOAD, new Code[] { source }, attributes);
			this.index = index;
		}
		
		@Override
		public SemanticType returnType() {
			return type.element(index);
		}
		
		@Override
		public Code clone(SemanticType.Tuple type, Op opcode, Code<?>[] operands) {
			return Load(type,operands[0],index,attributes());
		}
	}
	
	public final static class Quantifier extends Code<SemanticType> {
		public final Pair<SemanticType,Integer>[] types;
		
		private Quantifier(SemanticType type, Op opcode,
				Code<?> operand, Pair<SemanticType,Integer>[] types, Attribute... attributes) {
			super(type, opcode, new Code[] { operand }, attributes);
			if (opcode != Op.EXISTS && opcode != Op.FORALL) {
				throw new IllegalArgumentException(
						"invalid opcode for quantifier constructor");
			}
			this.types = types;
		}
		
		private Quantifier(SemanticType type, Op opcode, Code<?> operand,
				Pair<SemanticType,Integer>[] types, Collection<Attribute> attributes) {
			super(type, opcode, new Code[] { operand }, attributes);
			if (opcode != Op.EXISTS && opcode != Op.FORALL) {
				throw new IllegalArgumentException(
						"invalid opcode for quantifier constructor");
			}
			this.types = types;
		}
		
		@Override
		public SemanticType returnType() {
			return SemanticType.Bool;
		}
		
		@Override
		public Code clone(SemanticType type, Op opcode, Code<?>[] operands) {
			return Quantifier(type,opcode,operands[0],types,attributes());
		}
		
		@Override
		public Code substitute(Map<Integer, Code> binding) {
			Code operand = operands[0].substitute(binding);
			if (operand != operands[0]) {
				return new Quantifier(this.type, this.opcode, operand, types,
						attributes());
			} else {
				return this;
			}
		}
		
		@Override
		public Code instantiate(Map<String,SemanticType> binding) {
			Pair<SemanticType, Integer>[] nTypes = types;
			for (int i = 0; i != types.length; ++i) {
				Pair<SemanticType, Integer> p = nTypes[i];
				SemanticType ot = p.first();
				SemanticType nt = ot.substitute(binding);
				if (nt != ot) {
					if (types == nTypes) {
						nTypes = Arrays.copyOf(types, nTypes.length);
						nTypes[i] = new Pair<SemanticType, Integer>(nt,
								p.second());
					}
				}
			}
			
			Code operand = operands[0].instantiate(binding);
			if (operand != operands[0] || nTypes != types) {
				return new Quantifier(this.type, this.opcode, operand, nTypes,
						attributes());
			} else {
				return this;
			}
		}
	}
	
	public final static class FunCall extends Code<SemanticType.Function> {
		public final NameID nid;

		private FunCall(SemanticType.Function type, Code operand, NameID nid,
				Attribute... attributes) {
			super(type, Op.FUNCALL, new Code[] { operand }, attributes);
			this.nid = nid;
		}

		private FunCall(SemanticType.Function type, Code operand, NameID nid,
				Collection<Attribute> attributes) {
			super(type, Op.FUNCALL, new Code[] { operand }, attributes);
			this.nid = nid;
		}
		
		@Override
		public SemanticType returnType() {
			return type.to();
		}
		
		@Override
		public Code clone(SemanticType.Function type, Code.Op opcode,
				Code<?>[] operands) {
			return new FunCall(type, operands[0], nid, attributes());
		}		
	}
}
