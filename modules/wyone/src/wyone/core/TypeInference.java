package wyone.core;

import java.io.File;
import java.math.BigInteger;
import java.util.*;

import wyone.util.*;
import static wyone.util.SyntaxError.*;

public class TypeInference {
	private File file;

	// maps constructor names to their declared types.
	private final HashMap<String, Type.Term> terms = new HashMap<String, Type.Term>();

	// list of open classes
	private final HashSet<String> openClasses = new HashSet<String>();
	
	// type hiearchy
	private final Type.Hierarchy hierarchy = new Type.Hierarchy();
	
	// globals contains the list of global variables
	// private final HashMap<String,Type> globals = new HashMap();

	public void infer(SpecFile spec) {
		file = spec.file;

		for (SpecFile.Decl d : spec.declarations) {
			if (d instanceof SpecFile.IncludeDecl) {
				SpecFile.IncludeDecl id = (SpecFile.IncludeDecl) d;
				File myFile = file; // save
				infer(id.file);
				file = myFile; // restore				
			} else if (d instanceof SpecFile.ClassDecl) {
				SpecFile.ClassDecl cd = (SpecFile.ClassDecl) d;
				Set<String> children = hierarchy.get(cd.name);
				if(children != null && !openClasses.contains(cd.name)) {
					syntaxError("class " + cd.name + " is not open",file,cd);
				} else if(children != null && !cd.isOpen) {
					syntaxError("class " + cd.name + " cannot be closed (i.e. it's already open)",file,cd);
				} else if(children != null) {
					children = new HashSet<String>(children);
				} else {
					children = new HashSet<String>();
				}
				children.addAll(cd.children);
				terms.put(cd.name, Type.T_TERM(cd.name, null));
				hierarchy.set(cd.name,children);
				
				if(cd.isOpen) {
					openClasses.add(cd.name);
				}
			} else if (d instanceof SpecFile.TermDecl) {
				SpecFile.TermDecl td = (SpecFile.TermDecl) d;
				terms.put(td.type.name, td.type);
			}
		}

		for (SpecFile.Decl d : spec.declarations) {
			if (d instanceof SpecFile.RewriteDecl) {
				infer((SpecFile.RewriteDecl) d);
			}
		}
	}

	public void infer(SpecFile.RewriteDecl rd) {
		
		HashMap<String,Type> environment = new HashMap<String,Type>();
		
		infer(rd.pattern,environment);
		
		for(SpecFile.RuleDecl rule : rd.rules) {
			infer(rule,environment);
		}
	}
	
	public Type.Ref infer(Pattern pattern, HashMap<String,Type> environment) {
		Type.Ref type;
		if(pattern instanceof Pattern.Leaf) {
			Pattern.Leaf p = (Pattern.Leaf) pattern; 
			type = Type.T_REF(p.type);
		} else if(pattern instanceof Pattern.Term) {
			Pattern.Term p = (Pattern.Term) pattern;
			Type.Ref d = null;
			if(p.data != null) {
				d = infer(p.data,environment);
			}
			if(p.variable != null) {
				environment.put(p.variable, d);
			}
			type = Type.T_REF(Type.T_TERM(p.name, d));
		} else {
			Pattern.Compound p = (Pattern.Compound) pattern;
			ArrayList<Type> types = new ArrayList<Type>();
			Pair<Pattern,String>[] p_elements = p.elements;
			for (int i=0;i!=p_elements.length;++i) {
				Pair<Pattern,String> ps = p_elements[i];
				String var = ps.second();
				Pattern pat = ps.first();
				Type t = infer(pat,environment);				
				types.add(t);
				if(var != null) {
					if(p.unbounded && (i+1) == p_elements.length) {
						if(p instanceof Pattern.List) {
							t = Type.T_LIST(true,t);
						} else if(pattern instanceof Pattern.Bag) {
							t = Type.T_BAG(true,t);
						} else {
							t = Type.T_SET(true,t);
						}
					} 
					environment.put(var,t);					
				}
			}
			if(p instanceof Pattern.List) { 
				type = Type.T_REF(Type.T_LIST(p.unbounded, types));
			} else if(p instanceof Pattern.Bag) { 
				type = Type.T_REF(Type.T_BAG(p.unbounded, types));
			} else {
				type = Type.T_REF(Type.T_SET(p.unbounded, types));
			}
		}
		pattern.attributes().add(new Attribute.Type(type));
		return type;
	}
	
	public void infer(SpecFile.RuleDecl rd, HashMap<String,Type> environment) {
		 environment = new HashMap<String,Type>(environment);
		ArrayList<Pair<String,Expr>> rd_lets = rd.lets;
		for(int i=0;i!=rd_lets.size();++i) {
			Pair<String,Expr> let = rd_lets.get(i);
			Pair<Expr,Type> p = resolve(let.second(),environment);
			rd.lets.set(i, new Pair(let.first(),p.first()));
			environment.put(let.first(), p.second());
		}
		
		if(rd.condition != null) {
			Pair<Expr,Type> p = resolve(rd.condition,environment);
			rd.condition = p.first();
			checkSubtype(Type.T_BOOL,p.second(),rd.condition);
		}
		
		Pair<Expr,Type> result = resolve(rd.result,environment);
		rd.result = result.first();
		
		// TODO: check result is a ref?
	}

	protected Pair<Expr,Type> resolve(Expr expr, HashMap<String,Type> environment) {
		try {
			Type result;
			if (expr instanceof Expr.Constant) {
				result = resolve((Expr.Constant) expr, environment);
			} else if (expr instanceof Expr.UnOp) {
				result = resolve((Expr.UnOp) expr, environment);
			} else if (expr instanceof Expr.BinOp) {
				result = resolve((Expr.BinOp) expr, environment);
			} else if (expr instanceof Expr.NaryOp) {
				result = resolve((Expr.NaryOp) expr, environment);
			} else if (expr instanceof Expr.ListUpdate) {
				result = resolve((Expr.ListUpdate) expr, environment);
			} else if (expr instanceof Expr.ListAccess) {
				Pair<Expr,Type> tmp = resolve((Expr.ListAccess) expr, environment);
				expr = tmp.first();
				result = tmp.second();
			} else if (expr instanceof Expr.Substitute) {
				result = resolve((Expr.Substitute) expr, environment);
			} else if (expr instanceof Expr.Constructor) {
				result = resolve((Expr.Constructor) expr, environment);
			} else if (expr instanceof Expr.Variable) {
				result = resolve((Expr.Variable) expr, environment);
			} else if (expr instanceof Expr.Comprehension) {
				result = resolve((Expr.Comprehension) expr, environment);
			} else {
				syntaxError("unknown code encountered (" + expr.getClass().getName() + ")", file, expr);
				return null;
			}
			expr.attributes().add(new Attribute.Type(result));
			return new Pair(expr,result); 
		} catch (SyntaxError se) {
			throw se;
		} catch (Exception ex) {
			syntaxError("internal failure", file, expr, ex);
		}
		return null; // dead code
	}

	protected Type resolve(Expr.Constant expr, HashMap<String, Type> environment) {
		Object v = expr.value;
		if (v instanceof Boolean) {
			return Type.T_BOOL;
		} else if (v instanceof BigInteger) {
			return Type.T_INT;
		} else if (v instanceof Double) {
			return Type.T_REAL;
		} else if (v instanceof String) {
			return Type.T_STRING;
		} else if (v instanceof Type) {
			Type t = (Type) v;
			return Type.T_META(t);
		} else {
			syntaxError("unknown constant encountered ("
					+ v.getClass().getName() + ")", file, expr);
			return null; // deadcode
		}
	}

	protected Type resolve(Expr.Constructor expr, HashMap<String,Type> environment) {
	
		if(expr.argument != null) {
			Pair<Expr,Type> arg_t = resolve(expr.argument,environment);
			expr.argument = arg_t.first();
			// TODO: type check parameter argument
		}

		Type.Term type = terms.get(expr.name);
	
		if (type == null) {
			syntaxError("function not declared", file, expr);
		}
		
		return type;
	}

	protected Type resolve(Expr.UnOp uop, HashMap<String,Type> environment) {		
		Pair<Expr,Type> p = resolve(uop.mhs,environment);
		uop.mhs = p.first();
		Type t = coerceToValue(p.second());
		
		switch (uop.op) {
		case LENGTHOF:
			if(!(t instanceof Type.Compound)) {
				syntaxError("collection type required",file,uop.mhs);
			}			
			t = Type.T_INT;
			break;
		case NEG:
			checkSubtype(Type.T_REAL, t, uop);
			break;
		case NOT:
			checkSubtype(Type.T_BOOL, t, uop);
			break;
		default:
			syntaxError("unknown unary expression encountered", file, uop);
		}
		
		return t;
	}

	protected Type resolve(Expr.BinOp bop, HashMap<String,Type> environment) {

		Pair<Expr,Type> p1 = resolve(bop.lhs,environment);
		Pair<Expr,Type> p2 = resolve(bop.rhs,environment);
		bop.lhs = p1.first();
		bop.rhs = p2.first();
		Type lhs_t = p1.second();
		Type rhs_t = p2.second();
		Type result;
		
		
		// first, deal with auto-unboxing
		switch(bop.op) {
		case EQ:
		case NEQ:
			break;
		case IN:
			rhs_t = coerceToValue(rhs_t);
			break;
		default:
			lhs_t = coerceToValue(lhs_t);
			rhs_t = coerceToValue(rhs_t);
		}
		
		// Second, do the thing for each
		
		switch (bop.op) {
		case ADD: {
			checkSubtype(Type.T_REAL, lhs_t, bop);
			checkSubtype(Type.T_REAL, rhs_t, bop);
			result = Type.leastUpperBound(lhs_t, rhs_t, hierarchy);
			break;
		}
		case DIV:
		case MUL: {
			checkSubtype(Type.T_REAL, lhs_t, bop);
			checkSubtype(Type.T_REAL, rhs_t, bop);
			result = Type.leastUpperBound(lhs_t, rhs_t, hierarchy);
			break;
		}
		case EQ:
		case NEQ: {
			result = Type.T_BOOL;
			break;
		}
		case LT:
		case LTEQ:
		case GT:
		case GTEQ: {
			checkSubtype(Type.T_REAL, lhs_t, bop);
			checkSubtype(Type.T_REAL, rhs_t, bop);
			result = Type.T_BOOL;
			break;
		}
		case AND:
		case OR: {
			checkSubtype(Type.T_BOOL, lhs_t, bop);
			checkSubtype(Type.T_BOOL, rhs_t, bop);
			result = Type.T_BOOL;
			break;
		}
		case APPEND: {
			if (lhs_t instanceof Type.Compound
					&& rhs_t instanceof Type.Compound) {
				result = Type.leastUpperBound(lhs_t, rhs_t, hierarchy);
			} else if (rhs_t instanceof Type.Compound) {
				// right append
				Type.Compound rhs_tc = (Type.Compound) rhs_t;
				result = Type.T_COMPOUND(rhs_tc,rhs_tc.unbounded,
						append(lhs_t, rhs_tc.elements));				
			} else if (lhs_t instanceof Type.Compound){
				// left append
				Type.Compound lhs_tc = (Type.Compound) rhs_t;
				if (!lhs_tc.unbounded) {
					result = Type.T_COMPOUND(lhs_tc, lhs_tc.unbounded,
							append(lhs_tc.elements, lhs_t));					
				} else {
					int length = lhs_tc.elements.length;
					Type[] nelements = Arrays.copyOf(lhs_tc.elements, length);
					length--;
					nelements[length] = Type.leastUpperBound(rhs_t,
							nelements[length], hierarchy);
					result = Type.T_COMPOUND(lhs_tc,true,nelements);					
				}
			} else {
				System.out.println("LHS: " + lhs_t);
				System.out.println("RHS: " + rhs_t);
				syntaxError("cannot append non-list types",file,bop);
				return null;
			}
			break;
		}
		case IS: {
			checkSubtype(lhs_t, rhs_t, bop);
			result = Type.T_BOOL;
			break;
		}
		case IN: {
			if(!(rhs_t instanceof Type.Compound)) {
				syntaxError("collection type required",file,bop.rhs);
			}
			Type.Compound tc = (Type.Compound) rhs_t; 
			checkSubtype(tc.element(hierarchy), lhs_t, bop);
			result = Type.T_BOOL;
			break;
		}
		default:
			syntaxError("unknown binary expression encountered", file, bop);
			return null; // dead-code
		}
		return result;
	}
	
	protected Type resolve(Expr.Comprehension expr, HashMap<String, Type> environment) {
		environment = (HashMap) environment.clone();
		ArrayList<Pair<Expr.Variable,Expr>> expr_sources = expr.sources;
		for(int i=0;i!=expr_sources.size();++i) {
			Pair<Expr.Variable,Expr> p = expr_sources.get(i);
			Expr.Variable variable = p.first();
			Expr source = p.second();
			if(environment.containsKey(variable.var)) {
				syntaxError("duplicate variable '" + variable + "'",file,variable);
			}
			Pair<Expr,Type> tmp = resolve(source,environment);
			expr_sources.set(i, new Pair(variable,tmp.first()));
			Type type = tmp.second();
			if(!(type instanceof Type.Compound)) {
				syntaxError("collection type required",file,source);
			}
			Type.Compound sourceType = (Type.Compound) type;
			Type elementType = sourceType.element(hierarchy);
			variable.attributes().add(new Attribute.Type(elementType));
			environment.put(variable.var, elementType);
		}
		
		if(expr.condition != null) {
			Pair<Expr,Type> p = resolve(expr.condition,environment);
			expr.condition = p.first();
			checkSubtype(Type.T_BOOL,p.second(),expr.condition);
		}
		
		switch(expr.cop) {
			case NONE:
			case SOME:
				return Type.T_BOOL;
			case SETCOMP: {
				Pair<Expr,Type> result = resolve(expr.value,environment);
				expr.value = result.first();
				return Type.T_SET(true,Type.T_REF(result.second()));
			}
			case BAGCOMP: {
				Pair<Expr,Type> result = resolve(expr.value,environment);
				expr.value = result.first();
				return Type.T_BAG(true,Type.T_REF(result.second()));
			}
			case LISTCOMP: {
				Pair<Expr,Type> result = resolve(expr.value,environment);
				expr.value = result.first();
				return Type.T_LIST(true,Type.T_REF(result.second()));
			}
			default:
				throw new IllegalArgumentException("unknown comprehension kind");
		}		
	}
	
	protected Pair<Expr,Type> resolve(Expr.ListAccess expr, HashMap<String, Type> environment) {
		Pair<Expr,Type> p2 = resolve(expr.index,environment);
		expr.index= p2.first();
		Type idx_t = p2.second();
		
		// First, a little check for the unusual case that this is, in fact, not
		// a list access but a constructor with a single element list valued
		// argument.
		
		if(expr.src instanceof Expr.Variable) {
			Expr.Variable v = (Expr.Variable) expr.src;
			if(!environment.containsKey(v.var) && terms.containsKey(v.var)) {
				// ok, this is a candidate
				ArrayList<Expr> arguments = new ArrayList<Expr>();
				arguments.add(expr.index);
				Expr argument = new Expr.NaryOp(Expr.NOp.LISTGEN, arguments,
						expr.attribute(Attribute.Source.class));
				resolve(argument,environment); // must succeed ;)
				return new Pair<Expr,Type>(new Expr.Constructor(v.var, argument,
						expr.attributes()), terms.get(v.var));
			}
		}
		
		Pair<Expr,Type> p1 = resolve(expr.src,environment);
		expr.src = p1.first();

		Type src_t = p1.second();

		checkSubtype(Type.T_LISTANY, src_t, expr.src);
		checkSubtype(Type.T_INT, idx_t, expr.index);
		return new Pair(expr,((Type.List)src_t).element(hierarchy));
	}
	
	protected Type resolve(Expr.ListUpdate expr, HashMap<String, Type> environment) {
		
		Pair<Expr,Type> p1 = resolve(expr.src,environment);
		Pair<Expr,Type> p2 = resolve(expr.index,environment);
		Pair<Expr,Type> p3 = resolve(expr.value,environment);
		
		expr.src = p1.first();
		expr.index = p2.first();
		expr.value = p3.first();

		Type src_t = p1.second();
		Type idx_t = p2.second();
		Type value_t = p3.second();
		
		checkSubtype(Type.T_LISTANY, src_t, expr.src);
		checkSubtype(Type.T_INT, idx_t, expr.index);
		return Type.leastUpperBound(src_t, Type.T_LIST(true,value_t), hierarchy);
	}
	protected Type resolve(Expr.Substitute expr, HashMap<String, Type> environment) {
		Pair<Expr,Type> p1 = resolve(expr.src,environment);
		Pair<Expr,Type> p2 = resolve(expr.original,environment);
		Pair<Expr,Type> p3 = resolve(expr.replacement,environment);
		
		expr.src = p1.first();
		expr.original= p2.first();
		expr.replacement= p3.first();
		
		Type src_t = p1.second();
		
		// FIXME: need to generate something better here!!
		return src_t;
	}
	
	protected Type resolve(Expr.NaryOp expr, HashMap<String, Type> environment) {
		ArrayList<Expr> operands = expr.arguments;
		Type[] types = new Type[operands.size()];

		for (int i = 0; i != types.length; ++i) {
			Pair<Expr,Type> p = resolve(operands.get(i),environment);
			operands.set(i, p.first());
			types[i] = coerceToRef(p.second());
		}
		if(expr.op == Expr.NOp.LISTGEN) {
			return Type.T_LIST(false, types);
		} else if(expr.op == Expr.NOp.BAGGEN) {
			return Type.T_BAG(false, types);
		} else {
			return Type.T_SET(false, types);
		}
	}
	
	protected Type resolve(Expr.Variable code, HashMap<String, Type> environment) {
		Type result = environment.get(code.var);
		if (result == null) {
			Type.Term tmp = terms.get(code.var);
			if (tmp == null || tmp.data != null) {
				syntaxError("unknown variable encountered", file, code);
			}
			return tmp;
		} else {
			return result;
		}
	}

	public Type[] append(Type head, Type[] tail) {
		Type[] r = new Type[tail.length+1];
		System.arraycopy(tail,0,r,1,tail.length);
		r[0] = head;
		return r;
	}
	
	public Type[] append(Type[] head, Type tail) {
		Type[] r = new Type[head.length+1];
		System.arraycopy(head,0,r,0,head.length);
		r[head.length] = tail;
		return r;
	}
	
	/**
	 * Coerce the result of the given expression into a reference. In other words,
	 * if the result of the expression is a value then make a reference from it!
	 * 
	 * @param expr
	 * @param codes
	 */
	private Type.Ref coerceToRef(Type type) {		
		if(type instanceof Type.Ref) {
			return (Type.Ref) type;
		} else {
			return Type.T_REF(type);
		}
	}
	/**
	 * Coerce the result of the given expression into a value. In other words,
	 * if the result of the expression is a reference then derference it!
	 * 
	 * @param expr
	 * @param codes
	 */
	private Type coerceToValue(Type type) {		
		if(type instanceof Type.Ref) {
			Type.Ref ref = (Type.Ref) type;
			return ref.element;
		} else {
			return type;
		}
	}
	
	/**
	 * Check whether t1 :> t2; that is, whether t2 is a subtype of t1.
	 * 
	 * @param t1
	 * @param t2
	 * @param elem
	 */
	public void checkSubtype(Type t1, Type t2, SyntacticElement elem) {
		if (Type.isSubtype(t1, t2, hierarchy)) {
			return;
		}

		if (t1 instanceof Type.Ref) {
			t1 = ((Type.Ref) t1).element;
		}
		if (t2 instanceof Type.Ref) {
			t2 = ((Type.Ref) t2).element;
		}

		if (Type.isSubtype(t1, t2, hierarchy)) {
			return;
		}

		syntaxError("expecting type " + t1 + ", got type " + t2, file, elem);
	}
}