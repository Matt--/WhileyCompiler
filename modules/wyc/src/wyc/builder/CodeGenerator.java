// Copyright (c) 2011, David J. Pearce (djp@ecs.vuw.ac.nz)
// All rights reserved.
//
// Redistribution and use in source and binary forms, with or without
// modification, are permitted provided that the following conditions are met:
//    * Redistributions of source code must retain the above copyright
//      notice, this list of conditions and the following disclaimer.
//    * Redistributions in binary form must reproduce the above copyright
//      notice, this list of conditions and the following disclaimer in the
//      documentation and/or other materials provided with the distribution.
//    * Neither the name of the <organization> nor the
//      names of its contributors may be used to endorse or promote products
//      derived from this software without specific prior written permission.
//
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
// ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
// WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
// DISCLAIMED. IN NO EVENT SHALL DAVID J. PEARCE BE LIABLE FOR ANY
// DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
// (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
// ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
// (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
// SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

package wyc.builder;

import java.util.*;

import static wyc.lang.WhileyFile.internalFailure;
import static wyc.lang.WhileyFile.syntaxError;
import static wyil.util.ErrorMessages.*;
import wyc.lang.*;
import wyc.lang.Stmt.*;
import wyc.lang.WhileyFile.Context;
import wycc.lang.Attribute;
import wycc.lang.NameID;
import wycc.lang.SyntacticElement;
import wycc.lang.SyntaxError;
import wycc.util.Pair;
import wycc.util.ResolveError;
import wycc.util.Triple;
import wyfs.lang.Path;
import wyil.lang.*;

/**
 * <p>
 * Responsible for compiling the declarations, statements and expression found
 * in a WhileyFile into WyIL declarations and bytecode blocks. For example:
 * </p>
 * 
 * <pre>
 * type nat is (int x) where x >= 0
 * 
 * function f(nat x) => int:
 *    return x-1
 * </pre>
 * 
 * <p>
 * The code generator is responsible for generating the code for the constraint
 * on <code>nat</code>, as well as compiling the function's statements into
 * their corresponding WyIL bytecodes. For example, the code generated
 * constraint on type <code>nat</code> would look like this:
 * </p>
 * 
 * <pre>
 * type nat is int
 * where:
 *     load x
 *     const 0
 *     ifge goto exit
 *     fail("type constraint not satisfied")
 *  .exit:
 * </pre>
 * 
 * This WyIL bytecode simply compares the local variable x against 0. Here, x
 * represents the value held in a variable of type <code>nat</code>. If the
 * constraint fails, then the given message is printed.
 * 
 * @author David J. Pearce
 * 
 */
public final class CodeGenerator {

	/**
	 * The lambdas are anonymous functions used within statements and
	 * expressions in the source file. These are compiled into anonymised WyIL
	 * functions, since WyIL does not have an internal notion of a lambda.
	 */
	private final ArrayList<WyilFile.FunctionOrMethodDeclaration> lambdas = new ArrayList<WyilFile.FunctionOrMethodDeclaration>();
	
	/**
	 * The scopes stack is used for determining the correct scoping for continue
	 * and break statements. Whenever we begin translating a loop of some kind,
	 * a <code>LoopScope</code> is pushed on the stack. Once the translation of
	 * that loop is complete, this is then popped off the stack.
	 */
	private Stack<Scope> scopes = new Stack<Scope>();

	// =========================================================================
	// WhileyFile
	// =========================================================================		
	
	/**
	 * Generate a WyilFile from a given WhileyFile by translating all of the
	 * declarations, statements and expressions into WyIL declarations and
	 * bytecode blocks.
	 * 
	 * @param wf
	 *            The WhileyFile to be translated.
	 * @return
	 */
	public WyilFile generate(WhileyFile wf) {		
		ArrayList<WyilFile.Block> declarations = new ArrayList<WyilFile.Block>();

		// Go through each declaration and translate in the order of appearance.
		for (WhileyFile.Declaration d : wf.declarations) {
			try {
				if (d instanceof WhileyFile.Type) {
					declarations.add(generate((WhileyFile.Type) d));
				} else if (d instanceof WhileyFile.Constant) {
					declarations.add(generate((WhileyFile.Constant) d));
				} else if (d instanceof WhileyFile.FunctionOrMethod) {
					declarations.add(generate((WhileyFile.FunctionOrMethod) d));
				}
			} catch (SyntaxError se) {
				throw se;
			} catch (Throwable ex) {
				WhileyFile.internalFailure(ex.getMessage(),
						(WhileyFile.Context) d, d, ex);
			}
		}
		
		// Add any lambda functions which were used within some expression. Each
		// of these is guaranteed to have been given a unique and valid WyIL
		// name.
		declarations.addAll(lambdas);
		
		// Done
		return new WyilFile(wf.module, wf.filename, declarations);				
	}

	// =========================================================================
	// Constant Declarations
	// =========================================================================		

	/**
	 * Generate a WyilFile constant declaration from a WhileyFile constant
	 * declaration. This requires evaluating the given expression to produce a
	 * constant value. If this cannot be done, then a syntax error is raised to
	 * indicate an invalid constant declaration was encountered.
	 */
	private WyilFile.ConstantDeclaration generate(WhileyFile.Constant cd) {
		// TODO: this the point where were should an evaluator
		return new WyilFile.ConstantDeclaration(cd.modifiers(), cd.name(), cd.resolvedValue);
	}

	// =========================================================================
	// Type Declarations
	// =========================================================================		
	
	/**
	 * Generate a WyilFile type declaration from a WhileyFile type declaration.
	 * If a type invariant is given, then this will need to be translated into
	 * Wyil bytecode.
	 * 
	 * @param td
	 * @return
	 * @throws Exception
	 */
	private WyilFile.TypeDeclaration generate(WhileyFile.Type td)
			throws Exception {
		
		List<CodeBlock> invariant = new ArrayList<CodeBlock>();
		
		if (td.invariant != null) {
			// Create an empty invariant block to be populated during constraint
			// generation.
			CodeBlock block = new CodeBlock(1);
			// Setup the environment which maps source variables to block
			// registers. This is determined by allocating the root variable to
			// register 0, and then creating any variables declared in the type
			// pattern by from this root.
			Environment environment = new Environment();
			int root = environment.allocate(td.resolvedType.raw());
			addDeclaredVariables(root, td.pattern,
					td.resolvedType.raw(), environment, block);
			// Finally, translate the invariant expression.
			int target = generate(td.invariant, environment, block, td);
			// TODO: assign target register to something?
			invariant.add(block);
		}

		return new WyilFile.TypeDeclaration(td.modifiers(), td.name(),
				td.resolvedType.nominal(), invariant);
	}

	// =========================================================================
	// Function / Method Declarations
	// =========================================================================		
	
	private WyilFile.FunctionOrMethodDeclaration generate(
			WhileyFile.FunctionOrMethod fd) throws Exception {
		Type.FunctionOrMethod ftype = fd.resolvedType().raw();

		// The environment maintains the mapping from source-level variables to
		// the registers in WyIL block(s).
		Environment environment = new Environment();

		// ==================================================================
		// Generate pre-condition
		// ==================================================================

		// First, allocate parameters to registers in the current block
		for (int i = 0; i != fd.parameters.size(); ++i) {
			WhileyFile.Parameter p = fd.parameters.get(i);
			environment.allocate(ftype.params().get(i), p.name());
		}

		// TODO: actually translate pre-condition
		List<CodeBlock> precondition = new ArrayList<CodeBlock>();

		// ==================================================================
		// Generate post-condition
		// ==================================================================
		List<CodeBlock> postcondition = new ArrayList<CodeBlock>();

		if (fd.ensures.size() > 0) {
			// This indicates one or more explicit ensures clauses are given.
			// Therefore, we must translate each of these into Wyil bytecodes.

			// First, we need to create an appropriate environment within which
			// to translate the post-conditions.
			Environment postEnv = new Environment();
			int root = postEnv.allocate(fd.resolvedType().ret().raw());

			// FIXME: can't we reuse the original environment? Well, if we
			// allocated the return variable after the parameters then we
			// probably could.

			for (int i = 0; i != fd.parameters.size(); ++i) {
				WhileyFile.Parameter p = fd.parameters.get(i);
				postEnv.allocate(ftype.params().get(i), p.name());
			}

			CodeBlock block = new CodeBlock(postEnv.size());
			addDeclaredVariables(root, fd.ret, fd.resolvedType().ret().raw(),
					postEnv, block);

			for (Expr condition : fd.ensures) {
				// TODO: actually translate these conditions.
			}
			
			postcondition.add(block);
		}

		// ==================================================================
		// Generate body
		// ==================================================================

		ArrayList<CodeBlock> body = new ArrayList<CodeBlock>();
		CodeBlock block = new CodeBlock(fd.parameters.size());
		for (Stmt s : fd.statements) {
			generate(s, environment, block, fd);
		}

		// The following is sneaky. It guarantees that every method ends in a
		// return. For methods that actually need a value, this is either
		// removed as dead-code or remains and will cause an error.
		block.append(Code.Return(), attributes(fd));
		body.add(block);
		
		List<WyilFile.Case> ncases = new ArrayList<WyilFile.Case>();
		
		ncases.add(new WyilFile.Case(body, precondition, postcondition));

		// Done
		return new WyilFile.FunctionOrMethodDeclaration(fd.modifiers(),
				fd.name(), fd.resolvedType().raw(), ncases);
	}

	// =========================================================================
	// Statements
	// =========================================================================		
	
	/**
	 * Translate a source-level statement into a WyIL block, using a given
	 * environment mapping named variables to slots.
	 * 
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */
	public void generate(Stmt stmt, Environment environment, CodeBlock codes, Context context) {
		try {
			if (stmt instanceof VariableDeclaration) {
				generate((VariableDeclaration) stmt, environment, codes, context);
			} else if (stmt instanceof Assign) {
				generate((Assign) stmt, environment, codes, context);
			} else if (stmt instanceof Assert) {
				generate((Assert) stmt, environment, codes, context);
			} else if (stmt instanceof Assume) {
				generate((Assume) stmt, environment, codes, context);
			} else if (stmt instanceof Return) {
				generate((Return) stmt, environment, codes, context);
			} else if (stmt instanceof Debug) {
				generate((Debug) stmt, environment, codes, context);
			} else if (stmt instanceof IfElse) {
				generate((IfElse) stmt, environment, codes, context);
			} else if (stmt instanceof Switch) {
				generate((Switch) stmt, environment, codes, context);
			} else if (stmt instanceof TryCatch) {
				generate((TryCatch) stmt, environment, codes, context);
			} else if (stmt instanceof Break) {
				generate((Break) stmt, environment, codes, context);
			} else if (stmt instanceof Throw) {
				generate((Throw) stmt, environment, codes, context);
			} else if (stmt instanceof While) {
				generate((While) stmt, environment, codes, context);
			} else if (stmt instanceof DoWhile) {
				generate((DoWhile) stmt, environment, codes, context);
			} else if (stmt instanceof ForAll) {
				generate((ForAll) stmt, environment, codes, context);
			} else if (stmt instanceof Expr.MethodCall) {
				generate((Expr.MethodCall) stmt, Code.NULL_REG, environment, codes, context);								
			} else if (stmt instanceof Expr.FunctionCall) {
				generate((Expr.FunctionCall) stmt, Code.NULL_REG, environment, codes, context);								
			} else if (stmt instanceof Expr.IndirectMethodCall) {
				generate((Expr.IndirectMethodCall) stmt, Code.NULL_REG, environment, codes, context);								
			} else if (stmt instanceof Expr.IndirectFunctionCall) {
				generate((Expr.IndirectFunctionCall) stmt, Code.NULL_REG, environment, codes, context);								
			} else if (stmt instanceof Expr.New) {
				generate((Expr.New) stmt, environment, codes, context);
			} else if (stmt instanceof Skip) {
				generate((Skip) stmt, environment, codes, context);
			} else {
				// should be dead-code
				WhileyFile.internalFailure("unknown statement: "
						+ stmt.getClass().getName(), context, stmt);
			}
		} catch (ResolveError rex) {
			WhileyFile.syntaxError(rex.getMessage(), context, stmt, rex);
		} catch (SyntaxError sex) {
			throw sex;
		} catch (Exception ex) {			
			WhileyFile.internalFailure(ex.getMessage(), context, stmt, ex);
		}
	}
	
	/**
	 * Translate a variable declaration statement into a WyIL block. This only
	 * has an effect if an initialiser expression is given; otherwise, it's
	 * effectively a no-op.  Consider the following variable declaration:
	 * 
	 * <pre>
	 * int v = x + 1
	 * </pre>
	 * 
	 * This might be translated into the following WyIL bytecodes:
	 * 
	 * <pre>
	 * const %3 = 1                      
	 * add %4 = %0, %3                   
	 * return %4
	 * </pre>
	 * 
	 * Here, we see that variable <code>v</code> is allocated to register 4,
	 * whilst variable <code>x</code> is allocated to register 0.
	 * 
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */
	public void generate(VariableDeclaration s, Environment environment, CodeBlock codes, Context context) {
		// First, we allocate this variable to a given slot in the environment.
		int root = environment.allocate(s.type.raw());

		// Second, translate initialiser expression if it exists.
		if(s.expr != null) {
			int operand = generate(s.expr, environment, codes, context);						
			codes.append(Code.Assign(s.expr.result().raw(), root, operand),
					attributes(s));
			addDeclaredVariables(root, s.pattern, s.type.raw(), environment, codes);			
		} else {
			// The following is a little sneaky. Since we don't have an
			// initialiser, we cannot generate any codes for destructuring it.
			// Therefore, we create a dummy block into which any such codes are
			// placed and then we discard it. This is essentially a hack to
			// reuse the existing addDeclaredVariables method.
			addDeclaredVariables(root, s.pattern, s.type.raw(), environment,
					new CodeBlock(codes.numInputs()));			
		}
	}
	
	/**
	 * Translate an assignment statement into a WyIL block. This must consider
	 * the different forms of assignment which are permitted in Whiley,
	 * including:
	 * 
	 * <pre>
	 * x = e     // variable assignment
	 * x,y = e   // tuple assignment
	 * x.f = e   // field assignment
	 * x / y = e // rational assignment
	 * x[i] = e  // index-of assignment
	 * </pre>
	 * 
	 * As an example, consider the following index assignment:
	 * 
	 * <pre>
	 * xs[i + 1] = 1
	 * </pre>
	 * 
	 * This might be translated into the following WyIL bytecodes:
	 * 
	 * <pre>
	 * const %2 = 1                      
	 * const %4 = 1                      
	 * add %5 = %0, %4                   
	 * update %1[%5] %2       
	 * const %6 = 0                      
	 * return %6
	 * </pre>
	 * 
	 * Here, variable <code>i</code> is allocated to register 0, whilst variable
	 * <code>xs</code> is allocated to register 1. The result of the index
	 * expression <code>i+1</code> is stored in the temporary register 5.
	 * 
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */
	public void generate(Assign s, Environment environment, CodeBlock codes, Context context) {
		
		// First, we translate the right-hand side expression and assign it to a
		// temporary register.
		int operand = generate(s.rhs, environment, codes, context);
		
		// Second, we update the left-hand side of this assignment
		// appropriately.
		if (s.lhs instanceof Expr.AssignedVariable) {
			Expr.AssignedVariable v = (Expr.AssignedVariable) s.lhs;
			// This is the easiest case.  Having translated the right-hand side
			// expression, we now assign it directly to the register allocated
			// for variable on the left-hand side.						
			int target = environment.get(v.var);
			codes.append(Code.Assign(s.rhs.result().raw(), target, operand),
					attributes(s));			
		} else if(s.lhs instanceof Expr.RationalLVal) {
			Expr.RationalLVal tg = (Expr.RationalLVal) s.lhs;
			// Having translated the right-hand side expression, we now
			// destructure it using the numerator and denominator unary
			// bytecodes.
			Expr.AssignedVariable lv = (Expr.AssignedVariable) tg.numerator;
			Expr.AssignedVariable rv = (Expr.AssignedVariable) tg.denominator;
			
			codes.append(Code.UnArithOp(s.rhs.result()
					.raw(), environment.get(lv.var), operand, Code.UnArithKind.NUMERATOR),
					attributes(s));
			
			codes.append(Code.UnArithOp(s.rhs.result().raw(),
					environment.get(rv.var), operand,
					Code.UnArithKind.DENOMINATOR), attributes(s));
						
		} else if(s.lhs instanceof Expr.Tuple) {					
			Expr.Tuple tg = (Expr.Tuple) s.lhs;
			// Having translated the right-hand side expression, we now
			// destructure it using tupleload bytecodes and assign to those
			// variables on the left-hand side.
			ArrayList<Expr> fields = new ArrayList<Expr>(tg.fields);						
			for (int i = 0; i != fields.size(); ++i) {
				Expr.AssignedVariable v = (Expr.AssignedVariable) fields.get(i);
				codes.append(Code.TupleLoad((Type.EffectiveTuple) s.rhs
						.result().raw(), environment.get(v.var), operand, i),
						attributes(s));
			}		
		} else if (s.lhs instanceof Expr.IndexOf
				|| s.lhs instanceof Expr.FieldAccess) {
			// This is the more complicated case, since the left-hand side
			// expression is recursive. However, the WyIL update bytecode comes
			// to the rescue here. All we need to do is extract the variable
			// being updated and give this to the update bytecode. For example,
			// in the expression "x.y.f = e" we have that variable "x" is being
			// updated.
			ArrayList<String> fields = new ArrayList<String>();
			ArrayList<Integer> operands = new ArrayList<Integer>();
			Expr.AssignedVariable lhs = extractLVal(s.lhs, fields, operands,
					environment, codes, context);			
			int target = environment.get(lhs.var);			
			codes.append(Code.Update(lhs.type.raw(), target, operand,
					operands, lhs.afterType.raw(), fields), attributes(s));
		} else {
			WhileyFile.syntaxError("invalid assignment", context, s);
		}
	}

	/**
	 * This function recurses down the left-hand side of an assignment (e.g.
	 * x[i] = e, x.f = e, etc) with a complex lval. The primary goal is to
	 * identify the left-most variable which is actually being updated. A
	 * secondary goal is to collect the sequence of field names being updated,
	 * and translate any index expressions and store them in temporary
	 * registers.
	 * 
	 * @param e
	 *            The LVal being extract from.
	 * @param fields
	 *            The list of fields being used in the assignment.
	 *            Initially, this is empty and is filled by this method as it
	 *            traverses the lval.
	 * @param operands
	 *            The list of temporary registers in which evaluated index
	 *            expression are stored. Initially, this is empty and is filled
	 *            by this method as it traverses the lval.
	 * @param environment
	 *            Mapping from variable names to block registers.
	 * @param codes
	 *            Code block into which this statement is to be translated.
	 * @param context
	 *            Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */
	private Expr.AssignedVariable extractLVal(Expr.LVal e, ArrayList<String> fields,
			ArrayList<Integer> operands, Environment environment, CodeBlock codes,
			Context context) {

		if (e instanceof Expr.AssignedVariable) {
			Expr.AssignedVariable v = (Expr.AssignedVariable) e;
			return v;
		} else if (e instanceof Expr.Dereference) {
			Expr.Dereference pa = (Expr.Dereference) e;
			return extractLVal((Expr.LVal) pa.src, fields, operands, environment, codes, context);
		} else if (e instanceof Expr.IndexOf) {
			Expr.IndexOf la = (Expr.IndexOf) e;
			int operand = generate(la.index, environment, codes, context);
			Expr.AssignedVariable l = extractLVal((Expr.LVal) la.src, fields, operands,
					environment, codes, context);
			operands.add(operand);
			return l;
		} else if (e instanceof Expr.FieldAccess) {
			Expr.FieldAccess ra = (Expr.FieldAccess) e;
			Expr.AssignedVariable r = extractLVal((Expr.LVal) ra.src, fields, operands,
					environment, codes, context);
			fields.add(ra.name);
			return r;
		} else {
			WhileyFile.syntaxError(errorMessage(INVALID_LVAL_EXPRESSION),
					context, e);
			return null; // dead code
		}
	}
	
	/**
	 * Translate an assert statement into WyIL bytecodes.
	 * 
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */
	public void generate(Assert s, Environment environment, CodeBlock codes,
			Context context) {
		// TODO: implement me
	}

	/**
	 * Translate an assume statement into WyIL bytecodes.
	 * 
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */
	public void generate(Assume s, Environment environment, CodeBlock codes,
			Context context) {
		// TODO: need to implement this translation.
	}
	
	/**
	 * Translate a return statement into WyIL bytecodes. In the case that a
	 * return expression is provided, then this is first translated and stored
	 * in a temporary register. Consider the following return statement:
	 * 
	 * <pre>
	 * return i * 2
	 * </pre>
	 * 
	 * This might be translated into the following WyIL bytecodes:
	 * 
	 * <pre>
	 * const %3 = 2                      
	 * mul %4 = %0, %3                   
	 * return %4
	 * </pre>
	 * 
	 * Here, we see that variable <code>I</code> is allocated to register 0,
	 * whilst the result of the expression <code>i * 2</code> is stored in
	 * register 4.
	 * 
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */
	public void generate(Return s, Environment environment, CodeBlock codes,
			Context context) {

		if (s.expr != null) {			
			int operand = generate(s.expr, environment, codes, context);

			// Here, we don't put the type propagated for the return expression.
			// Instead, we use the declared return type of this function. This
			// has the effect of forcing an implicit coercion between the
			// actual value being returned and its required type.

			Type ret = ((WhileyFile.FunctionOrMethod) context).resolvedType()
					.raw().ret();

			codes.append(Code.Return(ret, operand), attributes(s));
		} else {
			codes.append(Code.Return(), attributes(s));
		}
	}

	/**
	 * Translate a skip statement into a WyIL nop bytecode.
	 * 
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */
	public void generate(Skip s, Environment environment, CodeBlock codes,
			Context context) {
		codes.append(Code.Nop, attributes(s));
	}

	/**
	 * Translate a debug statement into WyIL bytecodes. The debug expression is
	 * first translated and stored in a temporary register. Consider the
	 * following debug statement:
	 * 
	 * <pre>
	 * debug "Hello World"
	 * </pre>
	 * 
	 * This might be translated into the following WyIL bytecodes:
	 * 
	 * <pre>
	 * const %2 = "Hello World"       
	 * debug %2
	 * </pre>
	 * 
	 * Here, we see that debug expression is first stored into the temporary
	 * register 2.
	 *  
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */
	public void generate(Debug s, Environment environment,
			CodeBlock codes, Context context) {
		int operand = generate(s.expr, environment, codes, context);
		codes.append(Code.Debug(operand), attributes(s));
	}

	/**
	 * Translate an if statement into WyIL bytecodes. This is done by first
	 * translating the condition into one or more conditional branches. The true
	 * and false blocks are then translated and marked with labels. Finally, an
	 * exit label is provided to catch the fall-through case. Consider the
	 * following if statement:
	 * 
	 * <pre>
	 * if x+1 < 2:
	 *     x = x + 1
	 * ...
	 * </pre>
	 * 
	 * This might be translated into the following WyIL bytecodes:
	 * 
	 * <pre>
	 * const %3 = 1                      
	 * add %4 = %0, %3                   
	 * const %5 = 2                      
	 * ifge %4, %5 goto label0           
	 * const %7 = 1                      
	 * add %8 = %0, %7                   
	 * assign %0 = %8                   
	 * .label0                                 
	 *    ...
	 * </pre>
	 * 
	 * Here, we see that result of the condition is stored into temporary
	 * register 4, which is then used in the comparison. In the case the
	 * condition is false, control jumps over the true block; otherwise, it
	 * enters the true block and then (because there is no false block) falls
	 * through.
	 * 
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */
	public void generate(IfElse s, Environment environment, CodeBlock codes,
			Context context) {
		String falseLab = CodeBlock.freshLabel();
		String exitLab = s.falseBranch.isEmpty() ? falseLab : CodeBlock
				.freshLabel();

		generateCondition(falseLab, invert(s.condition), environment, codes, context);

		for (Stmt st : s.trueBranch) {
			generate(st, environment, codes, context);
		}
		if (!s.falseBranch.isEmpty()) {
			codes.append(Code.Goto(exitLab));
			codes.append(Code.Label(falseLab));
			for (Stmt st : s.falseBranch) {
				generate(st, environment, codes, context);
			}
		}

		codes.append(Code.Label(exitLab));
	}
	
	/**
	 * Translate a throw statement into WyIL bytecodes. The throw expression is
	 * first translated and stored in a temporary register.  Consider the
	 * following throw statement:
	 * 
	 * <pre>
	 * throw "Hello World"
	 * </pre>
	 * 
	 * This might be translated into the following WyIL bytecodes:
	 * 
	 * <pre>
	 * const %2 = "Hello World"       
	 * throw %2
	 * </pre>
	 * 
	 * Here, we see that the throw expression is first stored into the temporary
	 * register 2.
	 * 
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */
	public void generate(Throw s, Environment environment, CodeBlock codes, Context context) {
		int operand = generate(s.expr, environment, codes, context);
		codes.append(Code.Throw(s.expr.result().raw(), operand),
				s.attributes());
	}
	
	/**
	 * Translate a break statement into a WyIL unconditional branch bytecode.
	 * This requires examining the scope stack to determine the correct target
	 * for the branch. Consider the following use of a break statement:
	 * 
	 * <pre>
	 * while x < 10:
	 *    if x == 0:
	 *       break
	 *    x = x + 1     
	 * ...
	 * </pre>
	 * 
	 * This might be translated into the following WyIL bytecodes:
	 * 
	 * <pre>
	 * loop (%0)                               
	 *     const %3 = 10                     
	 *     ifge %0, %3 goto label0           
	 *     const %5 = 0                      
	 *     ifne %0, %5 goto label1           
	 *     goto label0                             
	 *     .label1                                 
	 *     const %7 = 1                      
	 *     add %8 = %0, %7                   
	 *     assign %0 = %8
	 * .label0                                                        
	 * ...
	 * </pre>
	 * 
	 * Here, we see that the break statement is translated into the bytecode
	 * "goto label0", which exits the loop. 
	 * 
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */	
	public void generate(Break s, Environment environment, CodeBlock codes, Context context) {
		BreakScope scope = findEnclosingScope(BreakScope.class);
		if (scope == null) {
			WhileyFile.syntaxError(errorMessage(BREAK_OUTSIDE_LOOP),
					context, s);
		}
		codes.append(Code.Goto(scope.label));
	}
	
	/**
	 * Translate a switch statement into WyIL bytecodes. This is done by first
	 * translating the switch expression and storing its result in a temporary
	 * register. Then, each case is translated in order of appearance. Consider
	 * the following switch statement:
	 * 
	 * <pre>
	 * switch x+1:
	 *     case 0,1:
	 *         return x+1
	 *     case 2:
	 *         x = x - 1
	 *     default:
	 *         x = 0
	 * </pre>
	 * 
	 * This might be translated into the following WyIL bytecodes:
	 * 
	 * <pre>
	 *     const %2 = 1                       
	 *     add %3 = %0, %2  
	 *     switch %3 0->label1, 1->label1, 2->label2, *->label0
	 * .label1                                 
	 *     const %3 = 1                       
	 *     add %4 = %0, %3                    
	 *     return %4                          
	 * .label2                                 
	 *     const %6 = 1                       
	 *     sub %7 = %0, %6                    
	 *     assign %0 = %7                     
	 *     goto label3                             
	 * .label0                                 
	 *     const %8 = 0                       
	 *     assign %0 = %8                     
	 *     goto label3                             
	 * .label3
	 * </pre>
	 * 
	 * Here, we see that switch expression is first stored into the temporary
	 * register 3. Then, each of the values 0 -- 2 is routed to the start of its
	 * block, with * representing the default case.
	 * 
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */
	public void generate(Switch s, Environment environment,
			CodeBlock codes, Context context) throws Exception {
		String exitLab = CodeBlock.freshLabel();
		int operand = generate(s.expr, environment, codes, context);
		String defaultTarget = exitLab;
		HashSet<Constant> values = new HashSet();
		ArrayList<Pair<Constant, String>> cases = new ArrayList();
		int start = codes.size();

		for (Stmt.Case c : s.cases) {
			if (c.expr.isEmpty()) {
				// A case with an empty match represents the default label. We
				// must check that we have not already seen a case with an empty
				// match (otherwise, we'd have two default labels ;)
				if (defaultTarget != exitLab) {
					WhileyFile.syntaxError(
							errorMessage(DUPLICATE_DEFAULT_LABEL),
							context, c);
				} else {
					defaultTarget = CodeBlock.freshLabel();
					codes.append(Code.Label(defaultTarget), attributes(c));
					for (Stmt st : c.stmts) {
						generate(st, environment, codes, context);
					}
					codes.append(Code.Goto(exitLab), attributes(c));
				}
				
			} else if (defaultTarget == exitLab) {
				String target = CodeBlock.freshLabel();
				codes.append(Code.Label(target), attributes(c));

				// Case statements in Whiley may have multiple matching constant
				// values. Therefore, we iterate each matching value and
				// construct a mapping from that to a label indicating the start
				// of the case body. 
				
				for (Constant constant : c.constants) {
					// Check whether this case constant has already been used as
					// a case constant elsewhere. If so, then report an error.
					if (values.contains(constant)) {
						WhileyFile.syntaxError(
								errorMessage(DUPLICATE_CASE_LABEL),
								context, c);
					}
					cases.add(new Pair(constant, target));
					values.add(constant);
				}

				for (Stmt st : c.stmts) {
					generate(st, environment, codes, context);
				}
				codes.append(Code.Goto(exitLab), attributes(c));
				
			} else {
				// This represents the case where we have another non-default
				// case after the default case. Such code cannot be executed,
				// and is therefore reported as an error.
				WhileyFile.syntaxError(errorMessage(UNREACHABLE_CODE),
						context, c);
			}
		}

		codes.insert(start, Code.Switch(s.expr.result().raw(), operand,
				defaultTarget, cases), attributes(s));
		codes.append(Code.Label(exitLab), attributes(s));
	}
	
	/**
	 * Translate a try-catch statement into WyIL bytecodes. Consider the
	 * following try-catch block:
	 * 
	 * <pre>
	 * try:
	 *     x = f(x+1)
	 * catch(string err):
	 *     return err
	 * ...
	 * </pre>
	 * 
	 * This might be translated into the following WyIL bytecodes:
	 * 
	 * <pre>
	 *     trycatch string->label4                 
	 *         const %4 = 1                      
	 *         add %5 = %0, %4                   
	 *         invoke %2 = (%5) test:f : function(int) => int throws string
	 *         goto label5                             
	 * .label4                                 
	 *     const %6 = 0                      
	 *     return %6                         
	 * .label5                                 
	 *     ...
	 * </pre>
	 * 
	 * Here, we see the trycatch bytecode routes exceptions to the start of
	 * their catch handlers. Likewise, at the end of the try block control
	 * branches over the catch handlers to continue on with the remainder of the
	 * function.
	 * 
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */
	public void generate(TryCatch s, Environment environment, CodeBlock codes, Context context) throws Exception {
		int start = codes.size();
		int exceptionRegister = environment.allocate(Type.T_ANY);
		String exitLab = CodeBlock.freshLabel();		
		
		for (Stmt st : s.body) {
			generate(st, environment, codes, context);
		}		
		codes.append(Code.Goto(exitLab),attributes(s));	
		String endLab = null;
		ArrayList<Pair<Type,String>> catches = new ArrayList<Pair<Type,String>>();
		for(Stmt.Catch c : s.catches) {			
			Code.Label lab;
			
			if(endLab == null) {
				endLab = CodeBlock.freshLabel();
				lab = Code.TryEnd(endLab);
			} else {
				lab = Code.Label(CodeBlock.freshLabel());
			}
			Type pt = c.type.raw();
			// TODO: deal with exception type constraints
			catches.add(new Pair<Type,String>(pt,lab.label));
			codes.append(lab, attributes(c));
			environment.put(exceptionRegister, c.variable);
			for (Stmt st : c.stmts) {
				generate(st, environment, codes, context);
			}
			codes.append(Code.Goto(exitLab),attributes(c));
		}
		
		codes.insert(start, Code.TryCatch(exceptionRegister,endLab,catches),attributes(s));
		codes.append(Code.Label(exitLab), attributes(s));
	}
	
	/**
	 * Translate a while loop into WyIL bytecodes. Consider the following use of
	 * a while statement:
	 * 
	 * <pre>
	 * while x < 10:
	 *    x = x + 1     
	 * ...
	 * </pre>
	 * 
	 * This might be translated into the following WyIL bytecodes:
	 * 
	 * <pre>
	 * loop (%0)                               
	 *     const %3 = 10                     
	 *     ifge %0, %3 goto label0           
	 *     const %7 = 1                      
	 *     add %8 = %0, %7                   
	 *     assign %0 = %8
	 * .label0                                                        
	 * ...
	 * </pre>
	 * 
	 * Here, we see that the evaluated loop condition is stored into temporary
	 * register 3 and that the condition is implemented using a conditional
	 * branch. Note that there is no explicit goto statement at the end of the
	 * loop body which loops back to the head (this is implicit in the loop
	 * bytecode).
	 * 
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */	
	public void generate(While s, Environment environment, CodeBlock codes,
			Context context) {
		String label = CodeBlock.freshLabel();
		String exit = CodeBlock.freshLabel();

		codes.append(Code.Loop(label, Collections.EMPTY_SET), attributes(s));

		generateCondition(exit, invert(s.condition), environment, codes,
				context);

		scopes.push(new BreakScope(exit));
		for (Stmt st : s.body) {
			generate(st, environment, codes, context);
		}
		scopes.pop(); // break

		// Must add NOP before loop end to ensure labels at the boundary
		// get written into Wyil files properly. See Issue #253.
		codes.append(Code.Nop);
		codes.append(Code.LoopEnd(label), attributes(s));
		codes.append(Code.Label(exit), attributes(s));
	}

	/**
	 * Translate a do-while loop into WyIL bytecodes. Consider the following use
	 * of a do-while statement:
	 * 
	 * <pre>
	 * do:
	 *    x = x + 1
	 * while x < 10     
	 * ...
	 * </pre>
	 * 
	 * This might be translated into the following WyIL bytecodes:
	 * 
	 * <pre>
	 * loop (%0)                               
	 *     const %2 = 1                      
	 *     add %3 = %0, %2                   
	 *     assign %0 = %3                   
	 *     const %5 = 10                     
	 *     ifge %3, %5 goto label0
	 * .label0                                                        
	 * ...
	 * </pre>
	 * 
	 * Here, we see that the evaluated loop condition is stored into temporary
	 * register 3 and that the condition is implemented using a conditional
	 * branch. Note that there is no explicit goto statement at the end of the
	 * loop body which loops back to the head (this is implicit in the loop
	 * bytecode).
	 * 
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */	
	public void generate(DoWhile s, Environment environment, CodeBlock codes,
			Context context) {		
		String label = CodeBlock.freshLabel();				
		String exit = CodeBlock.freshLabel();
		
		codes.append(Code.Loop(label, Collections.EMPTY_SET),
				attributes(s));
		
		scopes.push(new BreakScope(exit));	
		for (Stmt st : s.body) {
			generate(st, environment, codes, context);
		}		
		scopes.pop(); // break
		
		generateCondition(exit, invert(s.condition),
				environment, codes, context);
		
		// Must add NOP before loop end to ensure labels at the boundary
		// get written into Wyil files properly. See Issue #253.
		codes.append(Code.Nop);
		codes.append(Code.LoopEnd(label), attributes(s));
		codes.append(Code.Label(exit), attributes(s));		
	}
	
	/**
	 * Translate a forall loop into WyIL bytecodes. 
	 * 
	 * @param stmt
	 *            --- Statement to be translated.
	 * @param environment
	 *            --- Mapping from variable names to block registers.
	 * @param codes
	 *            --- Code block into which this statement is to be translated.
	 * @param context
	 *            --- Enclosing context of this statement (i.e. type, constant,
	 *            function or method declaration). The context is used to aid
	 *            with error reporting as it determines the enclosing file.
	 * @return
	 */
	public void generate(ForAll s, Environment environment,
			CodeBlock codes, Context context) {
		String label = CodeBlock.freshLabel();
		String exit = CodeBlock.freshLabel();
		
		int sourceRegister = generate(s.source, environment,
				codes, context);

		// FIXME: loss of nominal information
		Type.EffectiveCollection rawSrcType = s.srcType.raw();

		if (s.variables.size() > 1) {
			// this is the destructuring case

			// FIXME: support destructuring of lists and sets
			if (!(rawSrcType instanceof Type.EffectiveMap)) {
				WhileyFile.syntaxError(errorMessage(INVALID_MAP_EXPRESSION),
						context, s.source);
			}
			Type.EffectiveMap dict = (Type.EffectiveMap) rawSrcType;
			Type.Tuple element = (Type.Tuple) Type.Tuple(dict.key(),
					dict.value());
			int indexRegister = environment.allocate(element);
			codes.append(Code
					.ForAll((Type.EffectiveMap) rawSrcType, sourceRegister,
							indexRegister, Collections.EMPTY_SET, label),
					attributes(s));

			for (int i = 0; i < s.variables.size(); ++i) {
				String var = s.variables.get(i);
				int varReg = environment.allocate(element.element(i), var);
				codes.append(Code.TupleLoad(element, varReg, indexRegister, i),
						attributes(s));
			}
		} else {
			// easy case.
			int indexRegister = environment.allocate(rawSrcType.element(),
					s.variables.get(0));
			codes.append(Code.ForAll(s.srcType.raw(), sourceRegister,
					indexRegister, Collections.EMPTY_SET, label), attributes(s));
		}

		// FIXME: add a continue scope
		scopes.push(new BreakScope(exit));
		for (Stmt st : s.body) {
			generate(st, environment, codes, context);
		}
		scopes.pop(); // break

		// Must add NOP before loop end to ensure labels at the boundary
		// get written into Wyil files properly. See Issue #253.
		codes.append(Code.Nop);
		codes.append(Code.LoopEnd(label), attributes(s));
		codes.append(Code.Label(exit), attributes(s));				
	}

	// =========================================================================
	// Conditions
	// =========================================================================		

	/**
	 * Translate a source-level condition into a WyIL block, using a given
	 * environment mapping named variables to slots. If the condition evaluates
	 * to true, then control is transferred to the given target. Otherwise,
	 * control will fall through to the following bytecode. This method is
	 * necessary because the WyIL bytecode implementing comparisons are only
	 * available as conditional branches. For example, consider this if
	 * statement:
	 * 
	 * <pre>
	 * if x < y || x == y:
	 *     x = y
	 * else:
	 *     x = -y
	 * </pre>
	 * 
	 * This might be translated into the following WyIL bytecodes:
	 * 
	 * <pre>
	 *     iflt %0, %1 goto label0 : int           
	 *     ifne %0, %1 goto label1 : int           
	 * .label0                                                    
	 *     assign %0 = %1  : int                   
	 *     goto label2                             
	 * .label1                                 
	 *     neg %8 = %1 : int                       
	 *     assign %0 = %8  : int                   
	 * .label2
	 * </pre>
	 * 
	 * Here, we see that the condition "x < y || x == y" is broken down into two
	 * conditional branches (which additionally implement short-circuiting). The
	 * branches are carefully selected implement the semantics of the logical OR
	 * operator '||'. This function is responsible for translating conditional
	 * expressions like this into sequences of conditional branches using
	 * short-circuiting.
	 * 
	 * @param target
	 *            --- Target label to goto if condition is true. When the
	 *            condition is false, control falls simply through to the next
	 *            bytecode in sqeuence.
	 * @param condition
	 *            --- Source-level condition to be translated into a sequence of
	 *            one or more conditional branches.
	 * @param environment
	 *            --- Mapping from variable names to to slot numbers.
	 * @param codes
	 *            --- List of bytecodes onto which translation should be
	 *            appended.
	 * @return
	 */
	public void generateCondition(String target, Expr condition,
			Environment environment, CodeBlock codes, Context context) {
		try {
			
			// First, we see whether or not we can employ a special handler for
			// translating this condition.
			
			if (condition instanceof Expr.Constant) {
				generateCondition(target, (Expr.Constant) condition,
						environment, codes, context);
			} else if (condition instanceof Expr.UnOp) {
				generateCondition(target, (Expr.UnOp) condition, environment,
						codes, context);
			} else if (condition instanceof Expr.BinOp) {
				generateCondition(target, (Expr.BinOp) condition, environment,
						codes, context);
			} else if (condition instanceof Expr.Comprehension) {
				generateCondition(target, (Expr.Comprehension) condition,
						environment, codes, context);
			} else if (condition instanceof Expr.ConstantAccess
					|| condition instanceof Expr.LocalVariable
					|| condition instanceof Expr.AbstractInvoke
					|| condition instanceof Expr.AbstractIndirectInvoke
					|| condition instanceof Expr.FieldAccess
					|| condition instanceof Expr.IndexOf) {

				// This is the default case where no special handler applies. In
				// this case, we simply compares the computed value against
				// true. In some cases, we could actually do better. For
				// example, !(x < 5) could be rewritten into x >= 5.

				int r1 = generate(condition, environment, codes, context);
				int r2 = environment.allocate(Type.T_BOOL);
				codes.append(Code.Const(r2, Constant.V_BOOL(true)),
						attributes(condition));
				codes.append(Code.If(Type.T_BOOL, r1, r2, Code.Comparator.EQ,
						target), attributes(condition));

			} else {
				syntaxError(errorMessage(INVALID_BOOLEAN_EXPRESSION), context,
						condition);
			}

		} catch (SyntaxError se) {
			throw se;
		} catch (Exception ex) {
			internalFailure(ex.getMessage(), context, condition, ex);
		}
	}

	/**
	 * <p>
	 * Translate a source-level condition which is a constant (i.e.
	 * <code>true</code> or <code>false</code>) into a WyIL block, using a given
	 * environment mapping named variables to slots. This may seem like a
	 * perverse case, but it is permitted to allow selective commenting of code.
	 * </p>
	 * 
	 * <p>
	 * When the constant is true, an unconditional branch to the target is
	 * generated. Otherwise, nothing is generated and control falls through to
	 * the next bytecode in sequence.
	 * </p>
	 * 
	 * @param target
	 *            --- Target label to goto if condition is true. When the
	 *            condition is false, control falls simply through to the next
	 *            bytecode in sqeuence.
	 * @param condition
	 *            --- Source-level condition to be translated into a sequence of
	 *            one or more conditional branches.
	 * @param environment
	 *            --- Mapping from variable names to to slot numbers.
	 * @param codes
	 *            --- List of bytecodes onto which translation should be
	 *            appended.
	 * @return
	 */
	public void generateCondition(String target, Expr.Constant c,
			Environment environment, CodeBlock codes, Context context) {
		Constant.Bool b = (Constant.Bool) c.value;
		if (b.value) {
			codes.append(Code.Goto(target));
		} else {
			// do nout
		}
	}

	/**
	 * <p>
	 * Translate a source-level condition which is a binary expression into WyIL
	 * bytecodes, using a given environment mapping named variables to slots.
	 * </p>
	 * 
	 * @param target
	 *            --- Target label to goto if condition is true. When the
	 *            condition is false, control falls simply through to the next
	 *            bytecode in sqeuence.
	 * @param condition
	 *            --- Source-level condition to be translated into a sequence of
	 *            one or more conditional branches.
	 * @param environment
	 *            --- Mapping from variable names to to slot numbers.
	 * @param codes
	 *            --- List of bytecodes onto which translation should be
	 *            appended.
	 * @return
	 */
	public void generateCondition(String target, Expr.BinOp v,
			Environment environment, CodeBlock codes, Context context) throws Exception {

		Expr.BOp bop = v.op;

		if (bop == Expr.BOp.OR) {
			generateCondition(target, v.lhs, environment, codes, context);
			generateCondition(target, v.rhs, environment, codes, context);

		} else if (bop == Expr.BOp.AND) {
			String exitLabel = CodeBlock.freshLabel();
			generateCondition(exitLabel, invert(v.lhs), environment, codes, context);
			generateCondition(target, v.rhs, environment, codes, context);
			codes.append(Code.Label(exitLabel));

		} else if (bop == Expr.BOp.IS) {
			generateTypeCondition(target, v, environment, codes, context);

		} else {

			Code.Comparator cop = OP2COP(bop, v, context);

			if (cop == Code.Comparator.EQ
					&& v.lhs instanceof Expr.LocalVariable
					&& v.rhs instanceof Expr.Constant
					&& ((Expr.Constant) v.rhs).value == Constant.V_NULL) {
				// this is a simple rewrite to enable type inference.
				Expr.LocalVariable lhs = (Expr.LocalVariable) v.lhs;
				if (environment.get(lhs.var) == null) {
					syntaxError(errorMessage(UNKNOWN_VARIABLE), context, v.lhs);
				}
				int slot = environment.get(lhs.var);
				codes.append(
						Code.IfIs(v.srcType.raw(), slot, Type.T_NULL, target),
						attributes(v));
			} else if (cop == Code.Comparator.NEQ
					&& v.lhs instanceof Expr.LocalVariable
					&& v.rhs instanceof Expr.Constant
					&& ((Expr.Constant) v.rhs).value == Constant.V_NULL) {
				// this is a simple rewrite to enable type inference.
				String exitLabel = CodeBlock.freshLabel();
				Expr.LocalVariable lhs = (Expr.LocalVariable) v.lhs;
				if (environment.get(lhs.var) == null) {
					syntaxError(errorMessage(UNKNOWN_VARIABLE), context, v.lhs);
				}
				int slot = environment.get(lhs.var);
				codes.append(Code.IfIs(v.srcType.raw(), slot, Type.T_NULL,
						exitLabel), attributes(v));
				codes.append(Code.Goto(target));
				codes.append(Code.Label(exitLabel));
			} else {
				int lhs = generate(v.lhs, environment, codes, context);
				int rhs = generate(v.rhs, environment, codes, context);
				codes.append(Code.If(v.srcType.raw(), lhs, rhs, cop, target),
						attributes(v));
			}
		}
	}

	/**
	 * <p>
	 * Translate a source-level condition which represents a runtime type test
	 * (e.g. <code>x is int</code>) into WyIL bytecodes, using a given
	 * environment mapping named variables to slots. One subtlety of this arises
	 * when the lhs is a single variable. In this case, the variable will be
	 * retyped and, in order for this to work, we *must* perform the type test
	 * on the actual varaible, rather than a temporary.
	 * </p>
	 * 
	 * @param target
	 *            --- Target label to goto if condition is true. When the
	 *            condition is false, control falls simply through to the next
	 *            bytecode in sqeuence.
	 * @param condition
	 *            --- Source-level condition to be translated into a sequence of
	 *            one or more conditional branches.
	 * @param environment
	 *            --- Mapping from variable names to to slot numbers.
	 * @param codes
	 *            --- List of bytecodes onto which translation should be
	 *            appended.
	 * @return
	 */
	public void generateTypeCondition(String target, Expr.BinOp v,
			Environment environment, CodeBlock codes, Context context) throws Exception {
		int leftOperand;

		if (v.lhs instanceof Expr.LocalVariable) {
			
			// This is the case where the lhs is a single variable and, hence,
			// will be retyped by this operation. In this case, we must operate
			// on the original variable directly, rather than a temporary
			// variable (since, otherwise, we'll retype the temporary but not
			// the intended variable).			
			Expr.LocalVariable lhs = (Expr.LocalVariable) v.lhs;
			if (environment.get(lhs.var) == null) {
				syntaxError(errorMessage(UNKNOWN_VARIABLE), context, v.lhs);
			}
			leftOperand = environment.get(lhs.var);
		} else {
			// This is the general case whether the lhs is an arbitrary variable
			// and, hence, retyping does not apply. Therefore, we can simply
			// evaluate the lhs into a temporary register as per usual.			
			leftOperand = generate(v.lhs, environment, codes, context);
		}

		// Note, the type checker guarantees that the rhs is a type val, so the
		// following cast is always safe.
		Expr.TypeVal rhs = (Expr.TypeVal) v.rhs;
		
		codes.append(Code.IfIs(v.srcType.raw(), leftOperand,
				rhs.type.raw(), target), attributes(v));
	}

	/**
	 * <p>
	 * Translate a source-level condition which represents a unary condition
	 * into WyIL bytecodes, using a given environment mapping named variables to
	 * slots. Note, the only valid unary condition is logical not. To implement
	 * this, we simply generate the underlying condition and reroute its
	 * branch targets.
	 * </p>
	 * 
	 * @param target
	 *            --- Target label to goto if condition is true. When the
	 *            condition is false, control falls simply through to the next
	 *            bytecode in sqeuence.
	 * @param condition
	 *            --- Source-level condition to be translated into a sequence of
	 *            one or more conditional branches.
	 * @param environment
	 *            --- Mapping from variable names to to slot numbers.
	 * @param codes
	 *            --- List of bytecodes onto which translation should be
	 *            appended.
	 * @return
	 */
	public void generateCondition(String target, Expr.UnOp v,
			Environment environment, CodeBlock codes, Context context) {
		Expr.UOp uop = v.op;
		switch (uop) {
		case NOT:
			
			// What we do is generate the underlying expression whilst setting
			// its true destination to a temporary label. Then, for the fall
			// through case we branch to our true destination.  
			
			String label = CodeBlock.freshLabel();
			generateCondition(label, v.mhs, environment, codes, context);
			codes.append(Code.Goto(target));
			codes.append(Code.Label(label));
			return;
		}
		syntaxError(errorMessage(INVALID_BOOLEAN_EXPRESSION), context, v);
	}

	/**
	 * <p>
	 * Translate a source-level condition which represents a quantifier
	 * expression into WyIL bytecodes, using a given environment mapping named
	 * variables to slots.
	 * </p>
	 * 
	 * @param target
	 *            --- Target label to goto if condition is true. When the
	 *            condition is false, control falls simply through to the next
	 *            bytecode in sqeuence.
	 * @param condition
	 *            --- Source-level condition to be translated into a sequence of
	 *            one or more conditional branches.
	 * @param environment
	 *            --- Mapping from variable names to to slot numbers.
	 * @param codes
	 *            --- List of bytecodes onto which translation should be
	 *            appended.
	 * @return
	 */
	public void generateCondition(String target, Expr.Comprehension e,
			Environment environment, CodeBlock codes, Context context) {
		if (e.cop != Expr.COp.NONE && e.cop != Expr.COp.SOME && e.cop != Expr.COp.ALL) {
			syntaxError(errorMessage(INVALID_BOOLEAN_EXPRESSION), context, e);
		}

		ArrayList<Triple<Integer, Integer, Type.EffectiveCollection>> slots = new ArrayList();

		for (Pair<String, Expr> src : e.sources) {
			Nominal.EffectiveCollection srcType = (Nominal.EffectiveCollection) src
					.second().result();
			int srcSlot;
			int varSlot = environment.allocate(srcType.raw().element(),
					src.first());

			if (src.second() instanceof Expr.LocalVariable) {
				// this is a little optimisation to produce slightly better
				// code.
				Expr.LocalVariable v = (Expr.LocalVariable) src.second();
				if (environment.get(v.var) != null) {
					srcSlot = environment.get(v.var);
				} else {
					// fall-back plan ...
					srcSlot = generate(src.second(), environment, codes, context);
				}
			} else {
				srcSlot = generate(src.second(), environment, codes, context);
			}
			slots.add(new Triple<Integer, Integer, Type.EffectiveCollection>(
					varSlot, srcSlot, srcType.raw()));
		}

		ArrayList<String> labels = new ArrayList<String>();
		String loopLabel = CodeBlock.freshLabel();

		for (Triple<Integer, Integer, Type.EffectiveCollection> p : slots) {
			Type.EffectiveCollection srcType = p.third();
			String lab = loopLabel + "$" + p.first();
			codes.append(Code.ForAll(srcType, p.second(), p.first(),
					Collections.EMPTY_LIST, lab), attributes(e));
			labels.add(lab);
		}

		if (e.cop == Expr.COp.NONE) {
			String exitLabel = CodeBlock.freshLabel();
			generateCondition(exitLabel, e.condition, environment, codes, context);
			for (int i = (labels.size() - 1); i >= 0; --i) {
				// Must add NOP before loop end to ensure labels at the boundary
				// get written into Wyil files properly. See Issue #253.
				codes.append(Code.Nop);
				codes.append(Code.LoopEnd(labels.get(i)));
			}
			codes.append(Code.Goto(target));
			codes.append(Code.Label(exitLabel));
		} else if (e.cop == Expr.COp.SOME) {
			generateCondition(target, e.condition, environment, codes, context);
			for (int i = (labels.size() - 1); i >= 0; --i) {
				// Must add NOP before loop end to ensure labels at the boundary
				// get written into Wyil files properly. See Issue #253.
				codes.append(Code.Nop);
				codes.append(Code.LoopEnd(labels.get(i)));
			}
		} else if (e.cop == Expr.COp.ALL) {
			String exitLabel = CodeBlock.freshLabel();
			generateCondition(exitLabel, invert(e.condition), environment, codes, context);
			for (int i = (labels.size() - 1); i >= 0; --i) {
				// Must add NOP before loop end to ensure labels at the boundary
				// get written into Wyil files properly. See Issue #253.
				codes.append(Code.Nop);
				codes.append(Code.LoopEnd(labels.get(i)));
			}
			codes.append(Code.Goto(target));
			codes.append(Code.Label(exitLabel));		
		} // LONE and ONE will be harder
	}

	// =========================================================================
	// Expressions
	// =========================================================================		

	/**
	 * Translate a source-level expression into a WYIL bytecode block, using a
	 * given environment mapping named variables to registers. The result of the
	 * expression is stored in a given target register.
	 * 
	 * @param expression
	 *            --- Source-level expression to be translated
	 * @param environment
	 *            --- Mapping from variable names to to slot numbers.
	 * @param codes
	 *            --- List of bytecodes onto which translation should be
	 *            appended.
	 * 
	 * @return --- the register
	 */
	public int generate(Expr expression, Environment environment, CodeBlock codes, Context context) {
		try {
			if (expression instanceof Expr.Constant) {
				return generate((Expr.Constant) expression, environment, codes, context);
			} else if (expression instanceof Expr.LocalVariable) {
				return generate((Expr.LocalVariable) expression, environment,
						codes, context);
			} else if (expression instanceof Expr.ConstantAccess) {
				return generate((Expr.ConstantAccess) expression, environment,
						codes, context);
			} else if (expression instanceof Expr.Set) {
				return generate((Expr.Set) expression, environment, codes, context);
			} else if (expression instanceof Expr.List) {
				return generate((Expr.List) expression, environment, codes, context);
			} else if (expression instanceof Expr.SubList) {
				return generate((Expr.SubList) expression, environment, codes, context);
			} else if (expression instanceof Expr.SubString) {
				return generate((Expr.SubString) expression, environment, codes, context);
			} else if (expression instanceof Expr.BinOp) {
				return generate((Expr.BinOp) expression, environment, codes, context);
			} else if (expression instanceof Expr.LengthOf) {
				return generate((Expr.LengthOf) expression, environment, codes, context);
			} else if (expression instanceof Expr.Dereference) {
				return generate((Expr.Dereference) expression, environment,
						codes, context);
			} else if (expression instanceof Expr.Cast) {
				return generate((Expr.Cast) expression, environment, codes, context);
			} else if (expression instanceof Expr.IndexOf) {
				return generate((Expr.IndexOf) expression, environment, codes, context);
			} else if (expression instanceof Expr.UnOp) {
				return generate((Expr.UnOp) expression, environment, codes, context);
			} else if (expression instanceof Expr.FunctionCall) {
				return generate((Expr.FunctionCall) expression, environment,
						codes, context);
			} else if (expression instanceof Expr.MethodCall) {
				return generate((Expr.MethodCall) expression, environment,
						codes, context);
			} else if (expression instanceof Expr.IndirectFunctionCall) {
				return generate((Expr.IndirectFunctionCall) expression,
						environment, codes, context);
			} else if (expression instanceof Expr.IndirectMethodCall) {
				return generate((Expr.IndirectMethodCall) expression,
						environment, codes, context);
			} else if (expression instanceof Expr.Comprehension) {
				return generate((Expr.Comprehension) expression, environment,
						codes, context);
			} else if (expression instanceof Expr.FieldAccess) {
				return generate((Expr.FieldAccess) expression, environment,
						codes, context);
			} else if (expression instanceof Expr.Record) {
				return generate((Expr.Record) expression, environment, codes, context);
			} else if (expression instanceof Expr.Tuple) {
				return generate((Expr.Tuple) expression, environment, codes, context);
			} else if (expression instanceof Expr.Map) {
				return generate((Expr.Map) expression, environment, codes, context);
			} else if (expression instanceof Expr.FunctionOrMethod) {
				return generate((Expr.FunctionOrMethod) expression,
						environment, codes, context);
			} else if (expression instanceof Expr.Lambda) {
				return generate((Expr.Lambda) expression, environment, codes, context);
			} else if (expression instanceof Expr.New) {
				return generate((Expr.New) expression, environment, codes, context);
			} else {
				// should be dead-code
				internalFailure("unknown expression: "
						+ expression.getClass().getName(), context, expression);
			}
		} catch (ResolveError rex) {
			syntaxError(rex.getMessage(), context, expression, rex);
		} catch (SyntaxError se) {
			throw se;
		} catch (Exception ex) {
			internalFailure(ex.getMessage(), context, expression, ex);
		}

		return -1; // deadcode
	}

	public int generate(Expr.MethodCall expr, Environment environment,
			CodeBlock codes, Context context) throws ResolveError {
		int target = environment.allocate(expr.result().raw());
		generate(expr, target, environment, codes, context);
		return target;
	}

	public void generate(Expr.MethodCall expr, int target,
			Environment environment, CodeBlock codes, Context context) throws ResolveError {
		int[] operands = generate(expr.arguments, environment, codes, context);
		codes.append(Code.Invoke(expr.methodType.raw(), target, operands,
				expr.nid()), attributes(expr));
	}

	public int generate(Expr.FunctionCall expr, Environment environment,
			CodeBlock codes, Context context) throws ResolveError {
		int target = environment.allocate(expr.result().raw());
		generate(expr, target, environment, codes, context);
		return target;
	}

	public void generate(Expr.FunctionCall expr, int target,
			Environment environment, CodeBlock codes, Context context) throws ResolveError {
		int[] operands = generate(expr.arguments, environment, codes, context);
		codes.append(
				Code.Invoke(expr.functionType.raw(), target, operands,
						expr.nid()), attributes(expr));
	}

	public int generate(Expr.IndirectFunctionCall expr,
			Environment environment, CodeBlock codes, Context context) throws ResolveError {
		int target = environment.allocate(expr.result().raw());
		generate(expr, target, environment, codes, context);
		return target;
	}

	public void generate(Expr.IndirectFunctionCall expr, int target,
			Environment environment, CodeBlock codes, Context context) throws ResolveError {
		int operand = generate(expr.src, environment, codes, context);
		int[] operands = generate(expr.arguments, environment, codes, context);
		codes.append(Code.IndirectInvoke(expr.functionType.raw(), target,
				operand, operands), attributes(expr));
	}

	public int generate(Expr.IndirectMethodCall expr, Environment environment,
			CodeBlock codes, Context context) throws ResolveError {
		int target = environment.allocate(expr.result().raw());
		generate(expr, target, environment, codes, context);
		return target;
	}

	public void generate(Expr.IndirectMethodCall expr, int target,
			Environment environment, CodeBlock codes, Context context) throws ResolveError {
		int operand = generate(expr.src, environment, codes, context);
		int[] operands = generate(expr.arguments, environment, codes, context);
		codes.append(Code.IndirectInvoke(expr.methodType.raw(), target,
				operand, operands), attributes(expr));
	}

	private int generate(Expr.Constant expr, Environment environment,
			CodeBlock codes, Context context) {
		Constant val = expr.value;
		int target = environment.allocate(val.type());
		codes.append(Code.Const(target, expr.value), attributes(expr));
		return target;
	}

	private int generate(Expr.FunctionOrMethod expr, Environment environment,
			CodeBlock codes, Context context) {
		Type.FunctionOrMethod type = expr.type.raw();
		int target = environment.allocate(type);
		codes.append(
				Code.Lambda(type, target, Collections.EMPTY_LIST, expr.nid),
				attributes(expr));
		return target;
	}

	private int generate(Expr.Lambda expr, Environment environment, CodeBlock codes, Context context) {
		Type.FunctionOrMethod tfm = expr.type.raw();
		List<Type> tfm_params = tfm.params();
		List<WhileyFile.Parameter> expr_params = expr.parameters;
		
		// Create environment for the lambda body.
		ArrayList<Integer> operands = new ArrayList<Integer>();
		ArrayList<Type> paramTypes = new ArrayList<Type>();
		Environment benv = new Environment();
		for (int i = 0; i != tfm_params.size(); ++i) {
			Type type = tfm_params.get(i);
			benv.allocate(type, expr_params.get(i).name);
			paramTypes.add(type);
			operands.add(Code.NULL_REG);
		}
		for(Pair<Type,String> v : Exprs.uses(expr.body,context)) {
			if(benv.get(v.second()) == null) {
				Type type = v.first();
				benv.allocate(type,v.second());
				paramTypes.add(type);
				operands.add(environment.get(v.second()));
			}
		}

		// Generate body based on current environment
		CodeBlock bodyBlock = new CodeBlock(expr_params.size());
		ArrayList<CodeBlock> body = new ArrayList<CodeBlock>();
		body.add(bodyBlock);
		if(tfm.ret() != Type.T_VOID) {
			int target = generate(expr.body, benv, bodyBlock, context);
			bodyBlock.append(Code.Return(tfm.ret(), target), attributes(expr));		
		} else {
			bodyBlock.append(Code.Return(), attributes(expr));
		}
		
		// Create concrete type for private lambda function
		Type.FunctionOrMethod cfm;
		if(tfm instanceof Type.Function) {
			cfm = Type.Function(tfm.ret(),tfm.throwsClause(),paramTypes);
		} else {
			cfm = Type.Method(tfm.ret(),tfm.throwsClause(),paramTypes);
		}
				
		// Construct private lambda function using generated body
		int id = expr.attribute(Attribute.Source.class).start;
		String name = "$lambda" + id;
		ArrayList<Modifier> modifiers = new ArrayList<Modifier>();
		modifiers.add(Modifier.PRIVATE);
		ArrayList<WyilFile.Case> cases = new ArrayList<WyilFile.Case>();
		cases.add(new WyilFile.Case(body, Collections.EMPTY_LIST,
				Collections.EMPTY_LIST, attributes(expr)));
		WyilFile.FunctionOrMethodDeclaration lambda = new WyilFile.FunctionOrMethodDeclaration(
				modifiers, name, cfm, cases, attributes(expr));
		lambdas.add(lambda);
		Path.ID mid = context.file().module;
		NameID nid = new NameID(mid, name);
		
		// Finally, create the lambda
		int target = environment.allocate(tfm);
		codes.append(
				Code.Lambda(cfm, target, operands, nid),
				attributes(expr));
		return target;
	}

	private int generate(Expr.ConstantAccess expr, Environment environment,
			CodeBlock codes, Context context) throws ResolveError {
		Constant val = expr.value;
		int target = environment.allocate(val.type());
		codes.append(Code.Const(target, val), attributes(expr));
		return target;
	}

	private int generate(Expr.LocalVariable expr, Environment environment,
			CodeBlock codes, Context context) throws ResolveError {

		if (environment.get(expr.var) != null) {
			Type type = expr.result().raw();
			int operand = environment.get(expr.var);
			int target = environment.allocate(type);
			codes.append(Code.Assign(type, target, operand), attributes(expr));
			return target;
		} else {
			syntaxError(errorMessage(VARIABLE_POSSIBLY_UNITIALISED), context,
					expr);
			return -1;
		}
	}

	private int generate(Expr.UnOp expr, Environment environment, CodeBlock codes, Context context) {
		int operand = generate(expr.mhs, environment, codes, context);
		int target = environment.allocate(expr.result().raw());
		switch (expr.op) {
		case NEG:
			codes.append(Code.UnArithOp(expr.result().raw(), target, operand,
					Code.UnArithKind.NEG), attributes(expr));
			break;
		case INVERT:
			codes.append(Code.Invert(expr.result().raw(), target, operand),
					attributes(expr));
			break;
		case NOT:
			String falseLabel = CodeBlock.freshLabel();
			String exitLabel = CodeBlock.freshLabel();
			generateCondition(falseLabel, expr.mhs, environment, codes, context);
			codes.append(Code.Const(target, Constant.V_BOOL(true)),
					attributes(expr));
			codes.append(Code.Goto(exitLabel));
			codes.append(Code.Label(falseLabel));
			codes.append(Code.Const(target, Constant.V_BOOL(false)),
					attributes(expr));
			codes.append(Code.Label(exitLabel));
			break;
		default:
			// should be dead-code
			internalFailure("unexpected unary operator encountered", context,
					expr);
			return -1;
		}
		return target;
	}

	private int generate(Expr.LengthOf expr, Environment environment,
			CodeBlock codes, Context context) {
		int operand = generate(expr.src, environment, codes, context);
		int target = environment.allocate(expr.result().raw());
		codes.append(Code.LengthOf(expr.srcType.raw(), target, operand),
				attributes(expr));
		return target;
	}

	private int generate(Expr.Dereference expr, Environment environment,
			CodeBlock codes, Context context) {
		int operand = generate(expr.src, environment, codes, context);
		int target = environment.allocate(expr.result().raw());
		codes.append(Code.Dereference(expr.srcType.raw(), target, operand),
				attributes(expr));
		return target;
	}

	private int generate(Expr.IndexOf expr, Environment environment, CodeBlock codes, Context context) {
		int srcOperand = generate(expr.src, environment, codes, context);
		int idxOperand = generate(expr.index, environment, codes, context);
		int target = environment.allocate(expr.result().raw());
		codes.append(Code.IndexOf(expr.srcType.raw(), target, srcOperand,
				idxOperand), attributes(expr));
		return target;
	}

	private int generate(Expr.Cast expr, Environment environment, CodeBlock codes, Context context) {
		int operand = generate(expr.expr, environment, codes, context);
		Type from = expr.expr.result().raw();
		Type to = expr.result().raw();
		int target = environment.allocate(to);
		codes.append(Code.Convert(from, target, operand, to), attributes(expr));
		return target;
	}

	private int generate(Expr.BinOp v, Environment environment, CodeBlock codes, Context context)
			throws Exception {

		// could probably use a range test for this somehow
		if (v.op == Expr.BOp.EQ || v.op == Expr.BOp.NEQ || v.op == Expr.BOp.LT
				|| v.op == Expr.BOp.LTEQ || v.op == Expr.BOp.GT
				|| v.op == Expr.BOp.GTEQ || v.op == Expr.BOp.SUBSET
				|| v.op == Expr.BOp.SUBSETEQ || v.op == Expr.BOp.ELEMENTOF
				|| v.op == Expr.BOp.AND || v.op == Expr.BOp.OR) {
			String trueLabel = CodeBlock.freshLabel();
			String exitLabel = CodeBlock.freshLabel();
			generateCondition(trueLabel, v, environment, codes, context);
			int target = environment.allocate(Type.T_BOOL);
			codes.append(Code.Const(target, Constant.V_BOOL(false)),
					attributes(v));
			codes.append(Code.Goto(exitLabel));
			codes.append(Code.Label(trueLabel));
			codes.append(Code.Const(target, Constant.V_BOOL(true)),
					attributes(v));
			codes.append(Code.Label(exitLabel));
			return target;

		} else {

			Expr.BOp bop = v.op;
			int leftOperand = generate(v.lhs, environment, codes, context);
			int rightOperand = generate(v.rhs, environment, codes, context);
			Type result = v.result().raw();
			int target = environment.allocate(result);

			switch (bop) {
			case UNION:
				codes.append(Code.BinSetOp((Type.EffectiveSet) result, target,
						leftOperand, rightOperand, Code.BinSetKind.UNION),
						attributes(v));
				break;

			case INTERSECTION:
				codes.append(Code
						.BinSetOp((Type.EffectiveSet) result, target,
								leftOperand, rightOperand,
								Code.BinSetKind.INTERSECTION), attributes(v));
				break;

			case DIFFERENCE:
				codes.append(Code.BinSetOp((Type.EffectiveSet) result, target,
						leftOperand, rightOperand, Code.BinSetKind.DIFFERENCE),
						attributes(v));
				break;

			case LISTAPPEND:
				codes.append(Code.BinListOp((Type.EffectiveList) result,
						target, leftOperand, rightOperand,
						Code.BinListKind.APPEND), attributes(v));
				break;

			case STRINGAPPEND:
				Type lhs = v.lhs.result().raw();
				Type rhs = v.rhs.result().raw();
				Code.BinStringKind op;
				if (lhs == Type.T_STRING && rhs == Type.T_STRING) {
					op = Code.BinStringKind.APPEND;
				} else if (lhs == Type.T_STRING
						&& Type.isSubtype(Type.T_CHAR, rhs)) {
					op = Code.BinStringKind.LEFT_APPEND;
				} else if (rhs == Type.T_STRING
						&& Type.isSubtype(Type.T_CHAR, lhs)) {
					op = Code.BinStringKind.RIGHT_APPEND;
				} else {
					// this indicates that one operand must be explicitly
					// converted
					// into a string.
					op = Code.BinStringKind.APPEND;
				}
				codes.append(
						Code.BinStringOp(target, leftOperand, rightOperand, op),
						attributes(v));
				break;

			default:
				codes.append(Code.BinArithOp(result, target, leftOperand,
						rightOperand, OP2BOP(bop, v, context)), attributes(v));
			}

			return target;
		}
	}

	private int generate(Expr.Set expr, Environment environment, CodeBlock codes, Context context) {
		int[] operands = generate(expr.arguments, environment, codes, context);
		int target = environment.allocate(expr.result().raw());
		codes.append(Code.NewSet(expr.type.raw(), target, operands),
				attributes(expr));
		return target;
	}

	private int generate(Expr.List expr, Environment environment, CodeBlock codes, Context context) {
		int[] operands = generate(expr.arguments, environment, codes, context);
		int target = environment.allocate(expr.result().raw());
		codes.append(Code.NewList(expr.type.raw(), target, operands),
				attributes(expr));
		return target;
	}

	private int generate(Expr.SubList expr, Environment environment, CodeBlock codes, Context context) {
		int srcOperand = generate(expr.src, environment, codes, context);
		int startOperand = generate(expr.start, environment, codes, context);
		int endOperand = generate(expr.end, environment, codes, context);
		int target = environment.allocate(expr.result().raw());
		codes.append(Code.SubList(expr.type.raw(), target, srcOperand,
				startOperand, endOperand), attributes(expr));
		return target;
	}

	private int generate(Expr.SubString v, Environment environment, CodeBlock codes, Context context) {
		int srcOperand = generate(v.src, environment, codes, context);
		int startOperand = generate(v.start, environment, codes, context);
		int endOperand = generate(v.end, environment, codes, context);
		int target = environment.allocate(v.result().raw());
		codes.append(
				Code.SubString(target, srcOperand, startOperand, endOperand),
				attributes(v));
		return target;
	}

	private int generate(Expr.Comprehension e, Environment environment,
			CodeBlock codes, Context context) {

		// First, check for boolean cases which are handled mostly by
		// generateCondition.
		if (e.cop == Expr.COp.SOME || e.cop == Expr.COp.NONE || e.cop == Expr.COp.ALL) {
			String trueLabel = CodeBlock.freshLabel();
			String exitLabel = CodeBlock.freshLabel();
			generateCondition(trueLabel, e, environment, codes, context);
			int target = environment.allocate(Type.T_BOOL);
			codes.append(Code.Const(target, Constant.V_BOOL(false)),
					attributes(e));
			codes.append(Code.Goto(exitLabel));
			codes.append(Code.Label(trueLabel));
			codes.append(Code.Const(target, Constant.V_BOOL(true)),
					attributes(e));
			codes.append(Code.Label(exitLabel));
			return target;
		} else {

			// Ok, non-boolean case.
			ArrayList<Triple<Integer, Integer, Type.EffectiveCollection>> slots = new ArrayList();

			for (Pair<String, Expr> p : e.sources) {
				Expr src = p.second();
				Type.EffectiveCollection rawSrcType = (Type.EffectiveCollection) src
						.result().raw();
				int varSlot = environment.allocate(rawSrcType.element(),
						p.first());
				int srcSlot;

				if (src instanceof Expr.LocalVariable) {
					// this is a little optimisation to produce slightly better
					// code.
					Expr.LocalVariable v = (Expr.LocalVariable) src;
					if (environment.get(v.var) != null) {
						srcSlot = environment.get(v.var);
					} else {
						// fall-back plan ...
						srcSlot = generate(src, environment, codes, context);
					}
				} else {
					srcSlot = generate(src, environment, codes, context);
				}
				slots.add(new Triple(varSlot, srcSlot, rawSrcType));
			}

			Type resultType;
			int target = environment.allocate(e.result().raw());

			if (e.cop == Expr.COp.LISTCOMP) {
				resultType = e.type.raw();
				codes.append(Code.NewList((Type.List) resultType, target,
						Collections.EMPTY_LIST), attributes(e));
			} else {
				resultType = e.type.raw();
				codes.append(Code.NewSet((Type.Set) resultType, target,
						Collections.EMPTY_LIST), attributes(e));
			}

			// At this point, it would be good to determine an appropriate loop
			// invariant for a set comprehension. This is easy enough in the
			// case of
			// a single variable comprehension, but actually rather difficult
			// for a
			// multi-variable comprehension.
			//
			// For example, consider <code>{x+y | x in xs, y in ys, x<0 &&
			// y<0}</code>
			//
			// What is an appropriate loop invariant here?

			String continueLabel = CodeBlock.freshLabel();
			ArrayList<String> labels = new ArrayList<String>();
			String loopLabel = CodeBlock.freshLabel();

			for (Triple<Integer, Integer, Type.EffectiveCollection> p : slots) {
				String label = loopLabel + "$" + p.first();
				codes.append(Code.ForAll(p.third(), p.second(), p.first(),
						Collections.EMPTY_LIST, label), attributes(e));
				labels.add(label);
			}

			if (e.condition != null) {
				generateCondition(continueLabel, invert(e.condition),
						environment, codes, context);
			}

			int operand = generate(e.value, environment, codes, context);

			// FIXME: following broken for list comprehensions
			codes.append(Code.BinSetOp((Type.Set) resultType, target, target,
					operand, Code.BinSetKind.LEFT_UNION), attributes(e));

			if (e.condition != null) {
				codes.append(Code.Label(continueLabel));
			}

			for (int i = (labels.size() - 1); i >= 0; --i) {
				// Must add NOP before loop end to ensure labels at the boundary
				// get written into Wyil files properly. See Issue #253.
				codes.append(Code.Nop);
				codes.append(Code.LoopEnd(labels.get(i)));
			}

			return target;
		}
	}

	private int generate(Expr.Record expr, Environment environment, CodeBlock codes, Context context) {
		ArrayList<String> keys = new ArrayList<String>(expr.fields.keySet());
		Collections.sort(keys);
		int[] operands = new int[expr.fields.size()];
		for (int i = 0; i != operands.length; ++i) {
			String key = keys.get(i);
			Expr arg = expr.fields.get(key);
			operands[i] = generate(arg, environment, codes, context);
		}
		int target = environment.allocate(expr.result().raw());
		codes.append(Code.NewRecord(expr.result().raw(), target, operands),
				attributes(expr));
		return target;
	}

	private int generate(Expr.Tuple expr, Environment environment, CodeBlock codes, Context context) {
		int[] operands = generate(expr.fields, environment, codes, context);
		int target = environment.allocate(expr.result().raw());
		codes.append(Code.NewTuple(expr.result().raw(), target, operands),
				attributes(expr));
		return target;
	}

	private int generate(Expr.Map expr, Environment environment, CodeBlock codes, Context context) {
		int[] operands = new int[expr.pairs.size() * 2];
		for (int i = 0; i != expr.pairs.size(); ++i) {
			Pair<Expr, Expr> e = expr.pairs.get(i);
			operands[i << 1] = generate(e.first(), environment, codes, context);
			operands[(i << 1) + 1] = generate(e.second(), environment, codes, context);
		}
		int target = environment.allocate(expr.result().raw());
		codes.append(Code.NewMap(expr.result().raw(), target, operands),
				attributes(expr));
		return target;
	}

	private int generate(Expr.FieldAccess expr, Environment environment,
			CodeBlock codes, Context context) {
		int operand = generate(expr.src, environment, codes, context);
		int target = environment.allocate(expr.result().raw());
		codes.append(
				Code.FieldLoad(expr.srcType.raw(), target, operand, expr.name),
				attributes(expr));
		return target;
	}

	private int generate(Expr.New expr, Environment environment, CodeBlock codes, Context context)
			throws ResolveError {
		int operand = generate(expr.expr, environment, codes, context);
		int target = environment.allocate(expr.result().raw());
		codes.append(Code.NewObject(expr.type.raw(), target, operand));
		return target;
	}

	private int[] generate(List<Expr> arguments, Environment environment,
			CodeBlock codes, Context context) {
		int[] operands = new int[arguments.size()];
		for (int i = 0; i != operands.length; ++i) {
			Expr arg = arguments.get(i);
			operands[i] = generate(arg, environment, codes, context);
		}
		return operands;
	}

	// =========================================================================
	// Helpers
	// =========================================================================		
	
	@SuppressWarnings("incomplete-switch")
	private Code.BinArithKind OP2BOP(Expr.BOp bop, SyntacticElement elem, Context context) {
		switch (bop) {
		case ADD:
			return Code.BinArithKind.ADD;
		case SUB:
			return Code.BinArithKind.SUB;
		case MUL:
			return Code.BinArithKind.MUL;
		case DIV:
			return Code.BinArithKind.DIV;
		case REM:
			return Code.BinArithKind.REM;
		case RANGE:
			return Code.BinArithKind.RANGE;
		case BITWISEAND:
			return Code.BinArithKind.BITWISEAND;
		case BITWISEOR:
			return Code.BinArithKind.BITWISEOR;
		case BITWISEXOR:
			return Code.BinArithKind.BITWISEXOR;
		case LEFTSHIFT:
			return Code.BinArithKind.LEFTSHIFT;
		case RIGHTSHIFT:
			return Code.BinArithKind.RIGHTSHIFT;
		}
		syntaxError(errorMessage(INVALID_BINARY_EXPRESSION), context, elem);
		return null;
	}

	@SuppressWarnings("incomplete-switch")
	private Code.Comparator OP2COP(Expr.BOp bop, SyntacticElement elem, Context context) {
		switch (bop) {
		case EQ:
			return Code.Comparator.EQ;
		case NEQ:
			return Code.Comparator.NEQ;
		case LT:
			return Code.Comparator.LT;
		case LTEQ:
			return Code.Comparator.LTEQ;
		case GT:
			return Code.Comparator.GT;
		case GTEQ:
			return Code.Comparator.GTEQ;
		case SUBSET:
			return Code.Comparator.SUBSET;
		case SUBSETEQ:
			return Code.Comparator.SUBSETEQ;
		case ELEMENTOF:
			return Code.Comparator.ELEMOF;
		}
		syntaxError(errorMessage(INVALID_BOOLEAN_EXPRESSION), context, elem);
		return null;
	}

	/**
	 * The purpose of this method is to construct aliases for variables declared
	 * as part of type patterns. For example:
	 * 
	 * <pre>
	 * type tup as {int x, int y} where x < y
	 * </pre>
	 * 
	 * Here, variables <code>x</code> and <code>y</code> are declared as part of
	 * the type pattern, and we translate them into the aliases : $.x and $.y,
	 * where "$" is the root variable passed as a parameter.
	 * 
	 * @param src
	 * @param t
	 * @param environment
	 */
	public static void addDeclaredVariables(int root, TypePattern pattern, Type type,
			Environment environment, CodeBlock blk) {
		
		if(pattern instanceof TypePattern.Record) {
			TypePattern.Record tp = (TypePattern.Record) pattern;
			Type.Record tt = (Type.Record) type;
			for(TypePattern.Leaf element : tp.elements) {
				String fieldName = element.var.var;
				Type fieldType = tt.field(fieldName);
				int target = environment.allocate(fieldType);
				blk.append(Code.FieldLoad(tt, target, root, fieldName));
				addDeclaredVariables(target, element, fieldType, environment, blk);							
			}
		} else if(pattern instanceof TypePattern.Tuple){
			TypePattern.Tuple tp = (TypePattern.Tuple) pattern;
			Type.Tuple tt = (Type.Tuple) type;
			for(int i=0;i!=tp.elements.size();++i) {
				TypePattern element = tp.elements.get(i);
				Type elemType = tt.element(i);
				int target = environment.allocate(elemType);
				blk.append(Code.TupleLoad(tt, target, root, i));
				addDeclaredVariables(target, element, elemType, environment, blk);							
			}
		} else if(pattern instanceof TypePattern.Rational){
			TypePattern.Rational tp = (TypePattern.Rational) pattern;
			int num = environment.allocate(Type.T_INT);
			int den = environment.allocate(Type.T_INT);
			blk.append(Code.UnArithOp(Type.T_REAL, num, root, Code.UnArithKind.NUMERATOR));
			blk.append(Code.UnArithOp(Type.T_REAL, den, root, Code.UnArithKind.DENOMINATOR));
			addDeclaredVariables(num,tp.numerator,Type.T_INT,environment,blk);
			addDeclaredVariables(den,tp.denominator,Type.T_INT,environment,blk);			
		} else {
			// do nothing for leaf
			TypePattern.Leaf lp = (TypePattern.Leaf) pattern;
			if (lp.var != null) {
				environment.put(root, lp.var.var);
			}
		}						
	}
	
	@SuppressWarnings("incomplete-switch")
	private static Expr invert(Expr e) {
		if (e instanceof Expr.BinOp) {
			Expr.BinOp bop = (Expr.BinOp) e;
			Expr.BinOp nbop = null;
			switch (bop.op) {
			case AND:
				nbop = new Expr.BinOp(Expr.BOp.OR, invert(bop.lhs),
						invert(bop.rhs), attributes(e));
				break;
			case OR:
				nbop = new Expr.BinOp(Expr.BOp.AND, invert(bop.lhs),
						invert(bop.rhs), attributes(e));
				break;
			case EQ:
				nbop = new Expr.BinOp(Expr.BOp.NEQ, bop.lhs, bop.rhs,
						attributes(e));
				break;
			case NEQ:
				nbop = new Expr.BinOp(Expr.BOp.EQ, bop.lhs, bop.rhs,
						attributes(e));
				break;
			case LT:
				nbop = new Expr.BinOp(Expr.BOp.GTEQ, bop.lhs, bop.rhs,
						attributes(e));
				break;
			case LTEQ:
				nbop = new Expr.BinOp(Expr.BOp.GT, bop.lhs, bop.rhs,
						attributes(e));
				break;
			case GT:
				nbop = new Expr.BinOp(Expr.BOp.LTEQ, bop.lhs, bop.rhs,
						attributes(e));
				break;
			case GTEQ:
				nbop = new Expr.BinOp(Expr.BOp.LT, bop.lhs, bop.rhs,
						attributes(e));
				break;
			}
			if (nbop != null) {
				nbop.srcType = bop.srcType;
				return nbop;
			}
		} else if (e instanceof Expr.UnOp) {
			Expr.UnOp uop = (Expr.UnOp) e;
			switch (uop.op) {
			case NOT:
				return uop.mhs;
			}
		}

		Expr.UnOp r = new Expr.UnOp(Expr.UOp.NOT, e);
		r.type = Nominal.T_BOOL;
		return r;
	}
	
	/**
	 * The attributes method extracts those attributes of relevance to WyIL, and
	 * discards those which are only used for the wyc front end.
	 * 
	 * @param elem
	 * @return
	 */
	private static Collection<Attribute> attributes(SyntacticElement elem) {
		ArrayList<Attribute> attrs = new ArrayList<Attribute>();
		attrs.add(elem.attribute(Attribute.Source.class));
		return attrs;
	}
	
	public static final class Environment {
		private final HashMap<String, Integer> var2idx;
		private final ArrayList<Type> idx2type;

		public Environment() {
			var2idx = new HashMap<String, Integer>();
			idx2type = new ArrayList<Type>();
		}
		
		public Environment(Environment env) {
			var2idx = new HashMap<String, Integer>(env.var2idx);
			idx2type = new ArrayList<Type>(env.idx2type);
		}
		
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

		public int size() {
			return idx2type.size();
		}

		public Integer get(String v) {
			return var2idx.get(v);
		}

		public String get(int idx) {
			for (Map.Entry<String, Integer> e : var2idx.entrySet()) {
				int jdx = e.getValue();
				if (jdx == idx) {
					return e.getKey();
				}
			}
			return null;
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
	
	@SuppressWarnings("unchecked")
	private <T extends Scope> T findEnclosingScope(Class<T> c) {
		for(int i=scopes.size()-1;i>=0;--i) {
			Scope s = scopes.get(i);
			if(c.isInstance(s)) {
				return (T) s;
			}
		}
		return null;
	}	
	
	private abstract class Scope {}
	
	private class BreakScope extends Scope {
		public String label;
		public BreakScope(String l) { label = l; }
	}

	private class ContinueScope extends Scope {
		public String label;
		public ContinueScope(String l) { label = l; }
	}
}
