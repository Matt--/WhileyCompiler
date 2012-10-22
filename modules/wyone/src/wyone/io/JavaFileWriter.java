package wyone.io;

import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.math.BigInteger;
import java.util.*;

import wyone.util.*;
import wyone.core.Attribute;
import wyone.core.Expr;
import wyone.core.Pattern;
import wyone.core.SpecFile;
import wyone.core.Type;
import static wyone.core.Attribute.*;
import static wyone.core.SpecFile.*;

public class JavaFileWriter {
	private PrintWriter out;
	private HashSet<Type> typeTests = new HashSet<Type>();
	private HashSet<String> classSet = new HashSet<String>();
	private final HashMap<String, Set<String>> hierarchy = new HashMap<String, Set<String>>();
	
	public JavaFileWriter(Writer os) {
		out = new PrintWriter(os);
	}

	public JavaFileWriter(OutputStream os) {
		out = new PrintWriter(os);
	}

	public void write(SpecFile spec) {		
		if (!spec.pkg.equals("")) {
			myOut("package " + spec.pkg + ";");
			myOut("");
		}
		writeImports();
		myOut("public final class " + spec.name + " {");
		
		translate(spec);
		
		writeReduceDispatch(spec);
		writeInferenceDispatch(spec);
		writeTypeTests();
		writeSchema(spec);
		writeStatsInfo();
		writeMainMethod();
		myOut("}");
		out.flush();
	}
	
	private void translate(SpecFile spec) {
		for (ClassDecl cd : extractDecls(ClassDecl.class,spec)) {			
			classSet.add(cd.name);
			for (String child : cd.children) {
				Set<String> parents = hierarchy.get(child);
				if (parents == null) {
					parents = new HashSet<String>();
					hierarchy.put(child, parents);
				}
				parents.add(cd.name);
			}
		}

		for (Decl d : spec.declarations) {
			if(d instanceof IncludeDecl) {
				IncludeDecl id = (IncludeDecl) d;
				translate(id.file);
			} else  if (d instanceof TermDecl) {
				translate((TermDecl) d);
			} else if (d instanceof ClassDecl) {
				translate((ClassDecl) d);
			} else if (d instanceof RewriteDecl) {
				translate((RewriteDecl) d);
			}
		}
	}

	protected void writeImports() {
		myOut("import java.io.*;");
		myOut("import java.util.*;");
		myOut("import java.math.BigInteger;");
		myOut("import wyone.io.PrettyAutomataReader;");
		myOut("import wyone.io.PrettyAutomataWriter;");
		myOut("import wyone.core.*;");
		myOut("import static wyone.util.Runtime.*;");
		myOut();
	}
	
	public void writeReduceDispatch(SpecFile sf) {
		myOut(1, "public static boolean reduce(Automaton automaton) {");
		myOut(2, "boolean result = false;");
		myOut(2, "boolean changed = true;");
		myOut(2, "while(changed) {");
		myOut(3, "changed = false;");
		myOut(3, "for(int i=0;i<automaton.nStates();++i) {");
		myOut(4, "if(numSteps++ > MAX_STEPS) { return result; } // bail out");
		myOut(4, "if(automaton.get(i) == null) { continue; }");
		for(ReduceDecl rw : extractDecls(ReduceDecl.class,sf)) {
			Type type = rw.pattern.attribute(Attribute.Type.class).type;
			String mangle = type2HexStr(type);
			myOut(4,"");
			myOut(4, "if(typeof_" + mangle + "(i,automaton)) {");
			typeTests.add(type);
			myOut(5, "changed |= reduce_" + mangle + "(i,automaton);");				
			myOut(4, "}");
		}
		myOut(3,"}");
		myOut(3, "result |= changed;");
		myOut(2,"}");
		myOut(2, "automaton.compact();");
		myOut(2, "return result;");
		myOut(1, "}");
	}

	public void writeInferenceDispatch(SpecFile sf) {
		myOut(1, "public static boolean infer(Automaton automaton) {");
		myOut(2, "reset();");
		myOut(2, "boolean result = false;");
		myOut(2, "boolean changed = true;");
		myOut(2, "reduce(automaton);");
		myOut(2, "while(changed) {");
		myOut(3, "changed = false;");
		myOut(3, "for(int i=0;i<automaton.nStates();++i) {");
		myOut(4, "if(numSteps > MAX_STEPS) { return result; } // bail out");
		myOut(4, "if(automaton.get(i) == null) { continue; }");
		for(InferDecl rw : extractDecls(InferDecl.class,sf)) {
			Type type = rw.pattern.attribute(Attribute.Type.class).type;
			String mangle = type2HexStr(type);
			myOut(4,"");
			myOut(4, "if(typeof_" + mangle + "(i,automaton)) {");
			typeTests.add(type);
			myOut(5, "changed |= infer_" + mangle + "(i,automaton);");				
			myOut(4, "}");
		}
		myOut(3,"}");
		myOut(3, "result |= changed;");
		myOut(2,"}");
		myOut(2, "return result;");
		myOut(1, "}");		
	}
	
	public void translate(TermDecl decl) {
		myOut(1, "// term " + decl.type);
		myOut(1, "public final static int K_" + decl.type.name + " = "
				+ termCounter++ + ";");
		if (decl.type.data == null) {
			myOut(1, "public final static Automaton.Term " + decl.type.name
					+ " = new Automaton.Term(K_" + decl.type.name + ");");
		} else {
			Type.Ref data = decl.type.data;
			Type element = data.element;
			if(element instanceof Type.Compound) {
				// add two helpers
				myOut(1, "public final static int " + decl.type.name
						+ "(Automaton automaton, int... r0) {" );
				if(element instanceof Type.Set) { 
					myOut(2,"int r1 = automaton.add(new Automaton.Set(r0));");
				} else if(element instanceof Type.Bag) {
					myOut(2,"int r1 = automaton.add(new Automaton.Bag(r0));");
				} else {
					myOut(2,"int r1 = automaton.add(new Automaton.List(r0));");
				}
				myOut(2,"return automaton.add(new Automaton.Term(K_" + decl.type.name + ", r1));");
				myOut(1,"}");
				
				myOut(1, "public final static int " + decl.type.name
						+ "(Automaton automaton, List<Integer> r0) {" );
				if(element instanceof Type.Set) { 
					myOut(2,"int r1 = automaton.add(new Automaton.Set(r0));");
				} else if(element instanceof Type.Bag) {
					myOut(2,"int r1 = automaton.add(new Automaton.Bag(r0));");
				} else {
					myOut(2,"int r1 = automaton.add(new Automaton.List(r0));");
				}
				myOut(2,"return automaton.add(new Automaton.Term(K_" + decl.type.name + ", r1));");
				myOut(1,"}");
			} else if(element instanceof Type.Int) {
				// add two helpers
				myOut(1, "public final static int " + decl.type.name 
						+ "(Automaton automaton, long r0) {" );			
				myOut(2,"int r1 = automaton.add(new Automaton.Int(r0));");
				myOut(2,"return automaton.add(new Automaton.Term(K_" + decl.type.name + ", r1));");
				myOut(1,"}");
				
				myOut(1, "public final static int " + decl.type.name
						+ "(Automaton automaton, BigInteger r0) {" );	
				myOut(2,"int r1 = automaton.add(new Automaton.Int(r0));");
				myOut(2,"return automaton.add(new Automaton.Term(K_" + decl.type.name + ", r1));");
				myOut(1,"}");
			} else if(element instanceof Type.Strung) {
				// add two helpers
				myOut(1, "public final static int " + decl.type.name
						+ "(Automaton automaton, String r0) {" );	
				myOut(2,"int r1 = automaton.add(new Automaton.Strung(r0));");
				myOut(2,"return automaton.add(new Automaton.Term(K_" + decl.type.name + ", r1));");
				myOut(1,"}");
			} else {
				myOut(1, "public final static int " + decl.type.name
						+ "(Automaton automaton, " + type2JavaType(data) + " r0) {" );			
				myOut(2,"return automaton.add(new Automaton.Term(K_" + decl.type.name + ", r0));");
				myOut(1,"}");
			}
			
		}
		myOut();
	}

	private static int termCounter = 0;

	public void translate(ClassDecl decl) {
		String lin = "// " + decl.name + " as ";
		for (int i = 0; i != decl.children.size(); ++i) {
			String child = decl.children.get(i);
			if (i != 0) {
				lin += " | ";
			}
			lin += child;
		}
		myOut(1, lin);
		myOut();
	}

	public void translate(RewriteDecl decl) {
		boolean isReduction = decl instanceof ReduceDecl;
		Pattern.Term pattern = decl.pattern;
		Type param = pattern.attribute(Attribute.Type.class).type; 
		myOut(1, "// " + decl.pattern);
		
		String sig = type2HexStr(param) + "(" + type2JavaType(param) + " r0, Automaton automaton) {";
		
		if(decl instanceof ReduceDecl) {
			myOut(1, "public static boolean reduce_" + sig);
					
		} else {
			myOut(1, "public static boolean infer_" + sig);					
		}
		
		if(!isReduction) {
			myOut(1, "Automaton original = new Automaton(automaton);");
		}
		
		// setup the environment
		Environment environment = new Environment();
		int thus = environment.allocate(param,"this");
		
		// translate pattern
		int level = translate(2,pattern,thus,environment);
		
		// translate expressions
		myOut(1);		

		for(RuleDecl rd : decl.rules) {
			translate(level,rd,isReduction,environment);
		}
		
		// close the pattern match
		while(level > 2) {
			myOut(--level,"}");
		}
						
		myOut(level,"return false;");
		myOut(--level,"}");
		myOut();
	}
	
	public int translate(int level, Pattern p, int source, Environment environment) {
		if(p instanceof Pattern.Leaf) {
			return translate(level,(Pattern.Leaf) p,source,environment);
		} else if(p instanceof Pattern.Term) {
			return translate(level,(Pattern.Term) p,source,environment);
		} else if(p instanceof Pattern.Set) {
			return translate(level,(Pattern.Set) p,source,environment);
		} else if(p instanceof Pattern.Bag) {
			return translate(level,(Pattern.Bag) p,source,environment);
		} else  {
			return translate(level,(Pattern.List) p,source,environment);
		} 
	}
	
	public int translate(int level, Pattern.Leaf p, int source, Environment environment) {
		// do nothing?
		return level;
	}
	
	public int translate(int level, Pattern.Term pattern, int source, Environment environment) {
		Type.Ref<Type.Term> type = (Type.Ref) pattern.attribute(Attribute.Type.class).type;
		source = coerceFromRef(level, pattern, source, environment);
		if (type.element.data != null) {
			int target = environment.allocate(type.element.data, pattern.variable);
			myOut(level, type2JavaType(type.element.data) + " r" + target + " = r"
					+ source + ".contents;");
			return translate(level,pattern.data, target, environment);
		} else {
			return level;
		}
	}

	public int translate(int level, Pattern.BagOrSet pattern, int source, Environment environment) {
		Type.Ref<Type.Compound> type = (Type.Ref<Type.Compound>) pattern
				.attribute(Attribute.Type.class).type;
		source = coerceFromRef(level, pattern, source, environment);
		
		Pair<Pattern, String>[] elements = pattern.elements;
		
		// construct a for-loop for each fixed element to match
		int[] indices = new int[elements.length];
		for (int i = 0; i != elements.length; ++i) {
			boolean isUnbounded = pattern.unbounded && (i+1) == elements.length;
			Pair<Pattern, String> p = elements[i];
			Pattern pat = p.first();
			String var = p.second();
			Type.Ref pt = (Type.Ref) pat.attribute(Attribute.Type.class).type;			
			int index = environment.allocate(pt,var);
			String name = "i" + index;
			indices[i] = index;
			if(isUnbounded) {
				Type.Compound rt = pattern instanceof Pattern.Bag ? Type.T_BAG(true,pt) : Type.T_SET(true,pt);
				myOut(level, "int j" + index + " = 0;");
				myOut(level, "int[] t" + index + " = new int[r" + source + ".size()-" + i + "];");				
			}
			myOut(level++,"for(int " + name + "=0;" + name + "!=r" + source + ".size();++" + name + ") {");
			myOut(level, type2JavaType(pt) + " r" + index + " = r"
					+ source + ".get(" + name + ");");

			indent(level);out.print("if(");
			// check against earlier indices
			for(int j=0;j<i;++j) {
				out.print(name + " == i" + indices[j] + " || ");
			}
			// check matching type
			myOut("!typeof_" + type2HexStr(pt) + "(r" + index + ",automaton)) { continue; }");
			typeTests.add(pt);
			myOut(level);
			
			if(isUnbounded) {
				myOut(level,"t" + index + "[j" + index + "++] = r" + index + ";");
				myOut(--level,"}");
				if(pattern instanceof Pattern.Set) { 
					Type.Compound rt = Type.T_SET(true,pt);
					int rest = environment.allocate(rt,var);
					myOut(level, type2JavaType(rt) + " r" + rest + " = new Automaton.Set(t" + index + ");");
				} else {
					Type.Compound rt = Type.T_BAG(true,pt);
					int rest = environment.allocate(rt,var);
					myOut(level, type2JavaType(rt) + " r" + rest + " = new Automaton.Bag(t" + index + ");");
				}
			} else {
				level = translate(level++,pat,index,environment);
			}
		}	
		
		return level;
	}

	public int translate(int level, Pattern.List pattern, int source, Environment environment) {
		Type.Ref<Type.List> type = (Type.Ref<Type.List>) pattern
				.attribute(Attribute.Type.class).type;
		source = coerceFromRef(level, pattern, source, environment);
		
		Pair<Pattern, String>[] elements = pattern.elements;
		for (int i = 0; i != elements.length; ++i) {
			Pair<Pattern, String> p = elements[i];
			Pattern pat = p.first();
			String var = p.second();
			Type.Ref pt = (Type.Ref) pat.attribute(Attribute.Type.class).type;
			int element;
			if(pattern.unbounded && (i+1) == elements.length) {
				Type.List tc = Type.T_LIST(true, pt);
				element = environment.allocate(tc);
				myOut(level, type2JavaType(tc) + " r" + element + " = r"
						+ source + ".sublist(" + i + ");");
			} else {
				element = environment.allocate(pt);				
				myOut(level, type2JavaType(pt) + " r" + element + " = r"
						+ source + ".get(" + i + ");");
				level = translate(level,pat, element, environment);
			}			
			if (var != null) {
				environment.put(element, var);
			}
		}
		return level;
	}
	
	public void translate(int level, RuleDecl decl, boolean isReduce, Environment environment) {
		int thus = environment.get("this");
		for(Pair<String,Expr> let : decl.lets) {
			String letVar = let.first();
			Expr letExpr = let.second();
			int result = translate(2, letExpr, environment);
			environment.put(result, letVar);
		}
		if(decl.condition != null) {
			int condition = translate(level, decl.condition, environment);
			myOut(level++, "if(r" + condition + ") {");
		}
		int result = translate(level, decl.result, environment);
		result = coerceFromValue(level,decl.result,result,environment);
		
		myOut(level, "if(r" + thus + " != r" + result + ") {");
		myOut(level+1,"automaton.rewrite(r" + thus + ", r" + result + ");");
		if(isReduce) {			
			myOut(level+1, "numReductions++;");
			myOut(level+1, "return true;");
		} else {			
			myOut(level+1, "reduce(automaton);");
			myOut(level+1, "if(!automaton.equals(original)) { numInferences++; return true; }");
			myOut(level+1, "else { numMisinferences++; }");
		}
		myOut(level,"}");
		if(decl.condition != null) {
			myOut(--level,"}");
		}
	}
	
	public void writeSchema(SpecFile spec) {
		myOut(1,
				"// =========================================================================");
		myOut(1, "// Schema");
		myOut(1,
				"// =========================================================================");
		myOut();
		myOut(1, "public static final Type.Term[] SCHEMA = new Type.Term[]{");
		
		boolean firstTime=true;
		for(TermDecl td : extractDecls(TermDecl.class,spec)) {
			if (!firstTime) {
				myOut(",");
			}
			firstTime=false;
			indent(2);
			writeTypeSchema(td.type);				
		}
		myOut();
		myOut(1, "};");		
	}
	
	private <T extends Decl> ArrayList<T> extractDecls(Class<T> kind, SpecFile spec) {
		ArrayList r = new ArrayList();
		extractDecls(kind,spec,r);
		return r;
	}
	
	private <T extends Decl> void extractDecls(Class<T> kind, SpecFile spec, ArrayList<T> decls) {
		for(Decl d : spec.declarations) {
			if(kind.isInstance(d)) {
				decls.add((T)d);
			} else if(d instanceof IncludeDecl) {
				IncludeDecl id = (IncludeDecl) d;
				extractDecls(kind,id.file,decls);
			}
		}
	}
	
	public void writeTypeSchema(Type t) {
		if(t instanceof Type.Int) {
			out.print("Type.T_INT");
		} else if(t instanceof Type.Real) {
			out.print("Type.T_REAL");
		} else if(t instanceof Type.Strung) {
			out.print("Type.T_STRING");
		} else if(t instanceof Type.Any) {
			out.print("Type.T_ANY");
		} else if(t instanceof Type.Void) {
			out.print("Type.T_VOID");
		} else if(t instanceof Type.Ref) {
			Type.Ref ref = (Type.Ref) t;
			out.print("Type.T_REF(");
			writeTypeSchema(ref.element);
			out.print(")");
		} else if(t instanceof Type.Compound) {		
			Type.Compound compound = (Type.Compound) t;			
			if(compound instanceof Type.List) {
				out.print("Type.T_LIST(");
			} else {
				out.print("Type.T_SET(");							
			}
			if(compound.unbounded) {
				out.print("true");
			} else {
				out.print("false");
			}
			Type[] elements = compound.elements;
			for(int i=0;i!=elements.length;++i) {
				out.print(",");
				writeTypeSchema(elements[i]);
			}
			out.print(")");
		} else {
			Type.Term term = (Type.Term) t;
			out.print("Type.T_TERM(\"" + term.name + "\",");
			if(term.data != null) {
				writeTypeSchema(term.data);
			} else {
				out.print("null");
			}
			out.print(")");
		}
	}

	public int translate(int level, Expr code, Environment environment) {
		if (code instanceof Expr.Constant) {
			return translate(level,(Expr.Constant) code, environment);
		} else if (code instanceof Expr.UnOp) {
			return translate(level,(Expr.UnOp) code, environment);
		} else if (code instanceof Expr.BinOp) {
			return translate(level,(Expr.BinOp) code, environment);
		} else if (code instanceof Expr.NaryOp) {
			return translate(level,(Expr.NaryOp) code, environment);
		} else if (code instanceof Expr.Constructor) {
			return translate(level,(Expr.Constructor) code, environment);
		} else if (code instanceof Expr.ListAccess) {
			return translate(level,(Expr.ListAccess) code, environment);
		} else if (code instanceof Expr.ListUpdate) {
			return translate(level,(Expr.ListUpdate) code, environment);
		} else if (code instanceof Expr.Variable) {
			return translate(level,(Expr.Variable) code, environment);
		} else if(code instanceof Expr.Comprehension) {
			return translate(level,(Expr.Comprehension) code, environment);
		} else {
			throw new RuntimeException("unknown expression encountered - " + code);
		}
	}
	
	public int translate(int level, Expr.Constant code, Environment environment) {
		Type type = code.attribute(Attribute.Type.class).type;
		Object v = code.value;
		String rhs;
				
		if (v instanceof Boolean) {
			rhs = v.toString();
		} else if (v instanceof BigInteger) {
			BigInteger bi = (BigInteger) v;
			if(bi.bitLength() <= 64) {
				rhs = "new Automaton.Int(" + bi.longValue() + ")";
			} else {
				rhs = "new Automaton.Int(\"" + bi.toString() + "\")";	
			}
			
		} else {
			throw new RuntimeException("unknown constant encountered (" + v
					+ ")");
		}
		
		int target = environment.allocate(type);
		myOut(level,comment(type2JavaType(type) + " r" + target + " = " + rhs + ";",code.toString()));
		return target;
	}

	public int translate(int level, Expr.UnOp code, Environment environment) {
		Type type = code.attribute(Attribute.Type.class).type;
		int rhs = translate(level,code.mhs,environment);
		rhs = coerceFromRef(level,code.mhs, rhs, environment);
		String body;
		
		switch (code.op) {
		case LENGTHOF:
			body = "r" + rhs + ".lengthOf()";
			break;
		case NEG:
			body = "r" + rhs + ".negate()";
			break;
		case NOT:
			body = "!r" + rhs;
			break;
		default:
			throw new RuntimeException("unknown unary expression encountered");
		}
		
		int target = environment.allocate(type);
		myOut(level,comment(type2JavaType(type) + " r" + target + " = " + body + ";",code.toString()));
		return target;
	}

	public int translate(int level, Expr.BinOp code, Environment environment) {
		Type type = code.attribute(Attribute.Type.class).type;
		Type lhs_t = code.lhs.attribute(Attribute.Type.class).type;
		Type rhs_t = code.rhs.attribute(Attribute.Type.class).type;
		int lhs = translate(level,code.lhs,environment);
		
		if(code.op == Expr.BOp.IS && code.rhs instanceof Expr.Constant) {
			// special case for runtime type tests
			Expr.Constant c = (Expr.Constant) code.rhs;			
			Type test = (Type)c.value;
			lhs = coerceFromRef(level,code.lhs, lhs, environment);
			// TODO: cast is a hack :(
			String body = "typeof_" + type2HexStr(test) + "( (Automaton.State) r" + lhs +",automaton)";
			typeTests.add(test);
			int target = environment.allocate(type);			
			myOut(level,comment( type2JavaType(type) + " r" + target + " = " + body + ";",code.toString()));			
			return target;
		}
		
		
		int rhs = translate(level,code.rhs,environment);
		// First, convert operands into values (where appropriate)
		switch(code.op) {
		case EQ:
		case NEQ:
			if(lhs_t instanceof Type.Ref && rhs_t instanceof Type.Ref) {
				// OK to do nothing here...
			} else {
				lhs = coerceFromRef(level,code.lhs, lhs, environment);
				rhs = coerceFromRef(level,code.rhs, rhs, environment);
			}
			break;
		case APPEND:
			// append is a tricky case as we have support the non-symmetic cases
			// for adding a single element to the end or the beginning of a
			// list.
			lhs_t = Type.unbox(lhs_t);
			rhs_t = Type.unbox(rhs_t);
			
			if(lhs_t instanceof Type.Compound) {
				lhs = coerceFromRef(level,code.lhs, lhs, environment);				
			} else {
				lhs = coerceFromValue(level, code.lhs, lhs, environment);				
			}
			if(rhs_t instanceof Type.Compound) {
				rhs = coerceFromRef(level,code.rhs, rhs, environment);	
			} else {
				rhs = coerceFromValue(level,code.rhs, rhs, environment);
			}
			break;
		case IN:
			lhs = coerceFromValue(level,code.lhs,lhs,environment);
			rhs = coerceFromRef(level,code.rhs,rhs,environment);
			break;
		default:
			lhs = coerceFromRef(level,code.lhs,lhs,environment);
			rhs = coerceFromRef(level,code.rhs,rhs,environment);
		}
		
		// Second, construct the body of the computation
		String body;
						
		switch (code.op) {
		case ADD:
			body = "r" + lhs + ".add(r" + rhs + ")";
			break;
		case SUB:
			body = "r" + lhs + ".subtract(r" + rhs + ")";
			break;
		case MUL:
			body = "r" + lhs + ".multiply(r" + rhs + ")";
			break;
		case DIV:
			body = "r" + lhs + ".divide(r" + rhs + ")";
			break;
		case AND:
			body = "r" + lhs + " && r" + rhs ;
			break;
		case OR:
			body = "r" + lhs + " || r" + rhs ;
			break;
		case EQ:
			if(lhs_t instanceof Type.Ref && rhs_t instanceof Type.Ref) { 
				body = "r" + lhs + " == r" + rhs ;
			} else {
				body = "r" + lhs + ".equals(r" + rhs +")" ;
			}
			break;
		case NEQ:
			if(lhs_t instanceof Type.Ref && rhs_t instanceof Type.Ref) {
				body = "r" + lhs + " != r" + rhs ;
			} else {
				body = "!r" + lhs + ".equals(r" + rhs +")" ;
			}
			break;
		case LT:
			body = "r" + lhs + ".compareTo(r" + rhs + ")<0";
			break;
		case LTEQ:
			body = "r" + lhs + ".compareTo(r" + rhs + ")<=0";
			break;
		case GT:
			body = "r" + lhs + ".compareTo(r" + rhs + ")>0";
			break;
		case GTEQ:
			body = "r" + lhs + ".compareTo(r" + rhs + ")>=0";
			break;
		case APPEND: 
			if (lhs_t instanceof Type.Compound) {
				body = "r" + lhs + ".append(r" + rhs + ")";
			} else {
				body = "r" + rhs + ".appendFront(r" + lhs + ")";
			}
			break;
		case DIFFERENCE:
			body = "r" + lhs + ".removeAll(r" + rhs + ")";
			break;
		case IN:
			body = "r" + rhs + ".contains(r" + lhs + ")";
			break;
		default:
			throw new RuntimeException("unknown binary operator encountered: "
					+ code);
		}
		
		int target = environment.allocate(type);
		myOut(level,comment( type2JavaType(type) + " r" + target + " = " + body + ";",code.toString()));
		return target;
	}
	
	public int translate(int level, Expr.NaryOp code, Environment environment) {
		Type type = code.attribute(Attribute.Type.class).type;
		String body = "new Automaton.";				
		
		if(code.op == Expr.NOp.LISTGEN) { 
			body += "List(";
		} else if(code.op == Expr.NOp.BAGGEN) { 
			body += "Bag(";
		} else {
			body += "Set(";
		}
		
		List<Expr> arguments = code.arguments;
		for(int i=0;i!=arguments.size();++i) {
			if(i != 0) {
				body += ", ";
			}
			Expr argument = arguments.get(i);
			int reg = translate(level, argument, environment);
			reg = coerceFromValue(level, argument, reg, environment);
			body += "r" + reg;
		}
		
		int target = environment.allocate(type);
		myOut(level,comment(type2JavaType(type) + " r" + target + " = " + body + ");",code.toString()));
		return target;
	}
	
	public int translate(int level, Expr.ListAccess code, Environment environment) {
		Type type = code.attribute(Attribute.Type.class).type;
		int src = translate(level,code.src, environment);		
		int idx = translate(level,code.index, environment);
		src = coerceFromRef(level,code.src, src, environment);
		idx = coerceFromRef(level,code.index, idx, environment);
		
		String body = "r" + src + ".indexOf(r" + idx + ")";
				
		int target = environment.allocate(type);
		myOut(level,comment(type2JavaType(type) + " r" + target + " = " + body + ";",code.toString()));
		return target;
	}
	
	public int translate(int level, Expr.ListUpdate code, Environment environment) {
		Type type = code.attribute(Attribute.Type.class).type;
		int src = translate(level,code.src, environment);		
		int idx = translate(level,code.index, environment);
		int value = translate(level,code.value, environment);
		
		src = coerceFromRef(level,code.src, src, environment);
		idx = coerceFromRef(level,code.index, idx, environment);
		value = coerceFromValue(level,code.value, value, environment);
		
		String body = "r" + src + ".update(r" + idx + ", r" + value + ")";
				
		int target = environment.allocate(type);
		myOut(level,comment(type2JavaType(type) + " r" + target + " = " + body + ";",code.toString()));
		return target;
	}
	
	public int translate(int level, Expr.Constructor code,
			Environment environment) {
		Type type = code.attribute(Attribute.Type.class).type;
		String body;

		if (code.argument == null) {
			body = code.name;
		} else {
			int arg = translate(level, code.argument, environment);
			arg = coerceFromValue(level,code.argument,arg,environment);
			body = "new Automaton.Term(K_" + code.name + ",r"
					+  arg + ")";
		}

		int target = environment.allocate(type);
		myOut(level,  type2JavaType(type) + " r" + target + " = " + body + ";");
		return target;
	}
	
	public int translate(int level, Expr.Variable code, Environment environment) {
		Integer operand = environment.get(code.var);
		if(operand != null) {
			return environment.get(code.var);
		} else {
			Type type = code
					.attribute(Attribute.Type.class).type;
			int target = environment.allocate(type);
			myOut(level, type2JavaType(type) + " r" + target + " = " + code.var + ";");
			return target;
		}
	}
	
	public int translate(int level, Expr.Comprehension expr, Environment environment) {		
		Type type = expr.attribute(Attribute.Type.class).type;
		int target = environment
				.allocate(type);
		
		// first, translate all source expressions
		int[] sources = new int[expr.sources.size()];
		for(int i=0;i!=sources.length;++i) {
			Pair<Expr.Variable,Expr> p = expr.sources.get(i);
			int operand = translate(level,p.second(),environment);
			operand = coerceFromRef(level,p.second(),operand,environment);
			sources[i] = operand;									
		}
		// TODO: initialise result set
		myOut(level, "Automaton.List t" + target + " = new Automaton.List();");
		int startLevel = level;
		
		// initialise result register if needed
		switch(expr.cop) {		
		case NONE:
			myOut(level,type2JavaType(type) + " r" + target + " = true;");
			myOut(level,"outer:");
			break;
		case SOME:
			myOut(level,type2JavaType(type) + " r" + target + " = false;");
			myOut(level,"outer:");
			break;
		}
		
		// second, generate all the for loops
		for (int i = 0; i != sources.length; ++i) {
			Pair<Expr.Variable, Expr> p = expr.sources.get(i);
			Expr.Variable variable = p.first();
			Expr source = p.second();
			Type.Compound sourceType = (Type.Compound) source
					.attribute(Attribute.Type.class).type;
			Type elementType = variable.attribute(Attribute.Type.class).type;
			int index = environment.allocate(elementType, variable.var);
			myOut(level++, "for(int i" + index + "=0;i" + index + "<r"
					+ sources[i] + ".size();i" + index + "++) {");
			myOut(level, type2JavaType(elementType) + " r" + index + " = r"
					+ sources[i] + ".get(i" + index + ");");
		}
		
		if(expr.condition != null) {
			int condition = translate(level,expr.condition,environment);
			myOut(level++,"if(r" + condition + ") {");			
		}
		
		switch(expr.cop) {
		case SETCOMP:
		case BAGCOMP:
			int result = translate(level,expr.value,environment);
			result = coerceFromValue(level,expr.value,result,environment);
			myOut(level,"t" + target + ".add(r" + result + ");");
			break;
		case NONE:
			myOut(level,"r" + target + " = false;");
			myOut(level,"break outer;");
			break;
		case SOME:
			myOut(level,"r" + target + " = true;");
			myOut(level,"break outer;");
			break;
		}
		// finally, terminate all the for loops
		while(level > startLevel) {
			myOut(--level,"}");
		}

		switch(expr.cop) {
		case SETCOMP:
			myOut(level, type2JavaType(type) + " r" + target
				+ " = new Automaton.Set(t" + target + ".toArray());");
			break;
		case BAGCOMP:
			myOut(level, type2JavaType(type) + " r" + target
				+ " = new Automaton.Bag(t" + target + ".toArray());");
			break;		
		}

		return target;
	}
	
	protected void writeTypeTests() {
		myOut(1,
				"// =========================================================================");
		myOut(1, "// Type Tests");
		myOut(1,
				"// =========================================================================");
		myOut();

		HashSet<Type> worklist = new HashSet<Type>(typeTests);
		while (!worklist.isEmpty()) {
			Type t = worklist.iterator().next();
			worklist.remove(t);
			writeTypeTest(t, worklist);
		}
	}

	protected void writeTypeTest(Type type, HashSet<Type> worklist) {
		
		if (type instanceof Type.Any) {
			writeTypeTest((Type.Any)type,worklist);
		} else if (type instanceof Type.Int) {
			writeTypeTest((Type.Int)type,worklist);
		} else if (type instanceof Type.Strung) {
			writeTypeTest((Type.Strung)type,worklist);
		} else if (type instanceof Type.Ref) {
			writeTypeTest((Type.Ref)type,worklist);							
		} else if (type instanceof Type.Term) {
			writeTypeTest((Type.Term)type,worklist);
		} else if (type instanceof Type.Compound) {
			writeTypeTest((Type.Compound)type,worklist);							
		} else {
			throw new RuntimeException(
					"internal failure --- type test not implemented (" + type
							+ ")");
		}		
	}
	
	protected void writeTypeTest(Type.Any type, HashSet<Type> worklist) {
		String mangle = type2HexStr(type);
		myOut(1, "// " + type);
		myOut(1, "private static boolean typeof_" + mangle
				+ "(Automaton.State state, Automaton automaton) {");		
		myOut(2, "return true;");
		myOut(1, "}");
		myOut();
	}
	
	protected void writeTypeTest(Type.Int type, HashSet<Type> worklist) {
		String mangle = type2HexStr(type);
		myOut(1, "// " + type);
		myOut(1, "private static boolean typeof_" + mangle
				+ "(Automaton.State state, Automaton automaton) {");		
		myOut(2, "return state.kind == Automaton.K_INT;");
		myOut(1, "}");
		myOut();
	}
	
	protected void writeTypeTest(Type.Strung type, HashSet<Type> worklist) {
		String mangle = type2HexStr(type);
		myOut(1, "// " + type);
		myOut(1, "private static boolean typeof_" + mangle
				+ "(Automaton.State state, Automaton automaton) {");		
		myOut(2, "return state.kind == Automaton.K_STRING;");
		myOut(1, "}");
		myOut();
	}
	
	protected void writeTypeTest(Type.Ref type, HashSet<Type> worklist) {
		String mangle = type2HexStr(type);
		String elementMangle = type2HexStr(type.element);
		myOut(1, "// " + type);
		myOut(1, "private static boolean typeof_" + mangle
				+ "(int index, Automaton automaton) {");		
		myOut(2, "return typeof_" + elementMangle + "(automaton.get(index),automaton);");
		myOut(1, "}");
		myOut();	
		
		if (typeTests.add(type.element)) {
			worklist.add(type.element);
		}
	}
	
	protected void writeTypeTest(Type.Term type, HashSet<Type> worklist) {
		String mangle = type2HexStr(type);
		myOut(1, "// " + type);
		myOut(1, "private static boolean typeof_" + mangle
				+ "(Automaton.State state, Automaton automaton) {");
		
		HashSet<String> expanded = new HashSet<String>();
		expand(type.name, expanded);
		indent(2);
		out.print("if(state instanceof Automaton.Term && (");
		boolean firstTime = true;
		for (String n : expanded) {			
			myOut();
			indent(2);
			if(!firstTime) {
				out.print("   || state.kind == K_" + n);
			} else {
				firstTime=false;
				out.print(" state.kind == K_" + n);
			}
			
		}
		myOut(")) {");
		// FIXME: there is definitely a bug here since we need the offset within the automaton state
		if (type.data != null) {
			myOut(3,"int data = ((Automaton.Term)state).contents;");
			myOut(3,"if(typeof_" + type2HexStr(type.data) + "(data,automaton)) { return true; }");
			if (typeTests.add(type.data)) {
				worklist.add(type.data);
			}
		} else {
			myOut(3, "return true;");
		}		
		myOut(2, "}");
		myOut(2, "return false;");		
		myOut(1, "}");
		myOut();
	}
	
	protected void writeTypeTest(Type.Compound type, HashSet<Type> worklist) {
		String mangle = type2HexStr(type);
		myOut(1, "// " + type);
		myOut(1, "private static boolean typeof_" + mangle
				+ "(Automaton.State _state, Automaton automaton) {");		
		myOut(2, "if(_state instanceof Automaton.Compound) {");
		myOut(3, "Automaton.Compound state = (Automaton.Compound) _state;");
		
		Type[] tt_elements = type.elements;
		int min = tt_elements.length;
		if (type.unbounded) {
			myOut(3, "if(state.size() < " + (min - 1)
					+ ") { return false; }");
		} else {
			myOut(3, "if(state.size() != " + min + ") { return false; }");
		}
		
		int level = 3;
		if(type instanceof Type.List) {
			// easy, sequential match case
			for (int i = 0; i != tt_elements.length; ++i) {
				myOut(3, "int s" + i + " = " + i + ";");				
			}
		} else {
			for (int i = 0; i != tt_elements.length; ++i) {
				if(!type.unbounded || i+1 < tt_elements.length) {
					String idx = "s" + i;
					myOut(3+i, "for(int " + idx + "=0;" + idx + " < state.size();++" + idx + ") {");
					if(i > 0) {
						indent(3+i);out.print("if(");
						for(int j=0;j<i;++j) {
							if(j != 0) {
								out.print(" || ");
							}
							out.print(idx  + "==s" + j);
						}
						out.println(") { continue; }");
					}
					level++;
				}
			}			
		}
		
		myOut(level, "boolean result=true;");
		myOut(level, "for(int i=0;i!=state.size();++i) {");
		myOut(level+1, "int child = state.get(i);");
		for (int i = 0; i != tt_elements.length; ++i) {
			Type pt = tt_elements[i];
			String pt_mangle = type2HexStr(pt);
			if (type.unbounded && (i + 1) == tt_elements.length) {
				if(i == 0) {
					myOut(level+1, "{");
				} else {
					myOut(level+1, "else {");
				}
			} else if(i == 0){
				myOut(level+1, "if(i == s" + i + ") {");
			} else {
				myOut(level+1, "else if(i == s" + i + ") {");
			}
			myOut(level+2, "if(!typeof_" + pt_mangle
					+ "(child,automaton)) { result=false; break; }");
			myOut(level+1, "}");
			if (typeTests.add(pt)) {
				worklist.add(pt); 
			}
		}
		
		myOut(level,"}");
		myOut(level,"if(result) { return true; } // found match");
		if(type instanceof Type.Bag || type instanceof Type.Set) {
			for (int i = 0; i != tt_elements.length; ++i) {
				if(!type.unbounded || i+1 < tt_elements.length) {
					myOut(level - (i+1),"}");
				}
			}
		}

		myOut(2, "}");
		myOut(2,"return false;");
		myOut(1, "}");		
		myOut();
	}

	protected void expand(String name,HashSet<String> result) {
		//
		// FIXME: this could be made more efficient by not expanding things
		// which are already expanded!
		//
		ArrayList<String> worklist = new ArrayList<String>();
		worklist.add(name);
		while (!worklist.isEmpty()) {
			String n = worklist.get(0);
			worklist.remove(0);
			boolean matched = false;
			for (Map.Entry<String, Set<String>> e : hierarchy.entrySet()) {
				Set<String> parents = e.getValue();
				if (parents.contains(n)) {
					worklist.add(e.getKey());
					matched = true;
				}
			}
			if (!matched) {
				result.add(n);
			}
		}
	}

	protected void writeStatsInfo() {
		myOut(1,"public static int MAX_STEPS = 50000;");
		myOut(1,"public static int numSteps = 0;");
		myOut(1,"public static int numReductions = 0;");
		myOut(1,"public static int numInferences = 0;");
		myOut(1,"public static int numMisinferences = 0;");		
		
		myOut(1,"public static void reset() {");
		myOut(2,"numSteps = 0;");
		myOut(2,"numReductions = 0;");
		myOut(2,"numInferences = 0;");
		myOut(2,"numMisinferences = 0;");				
		myOut(1,"}");
	}
	
	protected void writeMainMethod() {
		myOut(1,
				"// =========================================================================");
		myOut(1, "// Main Method");
		myOut(1,
				"// =========================================================================");
		myOut();
		myOut(1, "public static void main(String[] args) throws IOException {");
		myOut(2, "try {");
		myOut(3,
				"PrettyAutomataReader reader = new PrettyAutomataReader(System.in,SCHEMA);");
		myOut(3,
				"PrettyAutomataWriter writer = new PrettyAutomataWriter(System.out,SCHEMA);");
		myOut(3, "Automaton automaton = reader.read();");
		myOut(3, "System.out.print(\"PARSED: \");");
		myOut(3, "writer.write(automaton);");
		myOut(3, "System.out.println();");
		myOut(3, "infer(automaton);");
		myOut(3, "System.out.print(\"REWROTE: \");");
		myOut(3, "writer.write(automaton);");
		myOut(3, "System.out.println();");
		myOut(3, "System.out.println(\"(Reductions=\" + numReductions + \", Inferences=\" + numInferences + \", Misinferences=\" + numMisinferences + \")\");");
		myOut(2, "} catch(PrettyAutomataReader.SyntaxError ex) {");
		myOut(3, "System.err.println(ex.getMessage());");
		myOut(2, "}");
		myOut(1, "}");
	}

	public String comment(String code, String comment) {
		int nspaces = 30 - code.length();
		String r = "";
		for(int i=0;i<nspaces;++i) {
			r += " ";
		}
		return code + r + " // " + comment;
	}
	
	public String type2HexStr(Type t) {
		String mangle = "";
		String str = Type.type2str(t);		
		for (int i = 0; i != str.length(); ++i) {
			char c = str.charAt(i);
			mangle = mangle + Integer.toHexString(c);
		}
		return mangle;
	}
	
	/**
	 * Convert a Wyone type into its equivalent Java type.
	 * 
	 * @param type
	 * @return
	 */
	public String type2JavaType(Type type) {
		if (type instanceof Type.Any) {
			return "Object";
		} else if (type instanceof Type.Int) {
			return "Automaton.Int";
		} else if (type instanceof Type.Bool) {
			// FIXME: not sure what to do here?
			return "boolean";
		} else if (type instanceof Type.Strung) {
			return "Automaton.Strung";
		} else if (type instanceof Type.Term) {
			return "Automaton.Term";
		} else if (type instanceof Type.Ref) {
			return "int";
		} else if (type instanceof Type.List) {
			return "Automaton.List";			
		} else if (type instanceof Type.Bag) {
			return "Automaton.Bag";
		} else if (type instanceof Type.Set) {
			return "Automaton.Set";
		}
		throw new RuntimeException("unknown type encountered: " + type);
	}
	
	public int coerceFromValue(int level, Expr expr, int register, Environment environment) {
		Type type = expr.attribute(Attribute.Type.class).type;
		if(type instanceof Type.Ref) {
			return register;
		} else {
			Type.Ref refType = Type.T_REF(type);
			int result = environment.allocate(refType);
			myOut(level, type2JavaType(refType) + " r" + result + " = automaton.add(r" + register + ");");
			return result;
		}
	}

	public int coerceFromRef(int level, SyntacticElement elem, int register, Environment environment) {
		Type type = elem.attribute(Attribute.Type.class).type;
		if(type instanceof Type.Ref) {
			Type.Ref refType = (Type.Ref) type;
			int result = environment.allocate(refType.element);
			String cast = type2JavaType(refType.element);
			myOut(level, cast + " r" + result + " = (" + cast + ") automaton.get(r" + register + ");");
			return result;
		} else {
			return register;
		}
	}
	
	protected void myOut() {
		myOut(0, "");
	}

	protected void myOut(int level) {
		myOut(level, "");
	}

	protected void myOut(String line) {
		myOut(0, line);
	}

	protected void myOut(int level, String line) {
		for (int i = 0; i < level; ++i) {
			out.print("\t");
		}
		out.println(line);
	}

	protected void indent(int level) {
		for (int i = 0; i < level; ++i) {
			out.print("\t");
		}
	}
	
	private static class Environment {
		private final HashMap<String, Integer> var2idx = new HashMap<String, Integer>();
		private final ArrayList<Type> idx2type = new ArrayList<Type>();

		public int allocate(Type t) {
			int idx = idx2type.size();
			idx2type.add(t);
			return idx;
		}

		public int allocate(Type t, String v) {
			int r = allocate(t);
			var2idx.put(v, r);
			return r;
		}

		public Integer get(String v) {
			return var2idx.get(v);
		}

		public void put(int idx, String v) {
			var2idx.put(v, idx);
		}

		public ArrayList<Type> asList() {
			return idx2type;
		}

		public String toString() {
			return idx2type.toString() + "," + var2idx.toString();
		}
	}
}