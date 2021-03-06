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

package wyil.transforms;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;

import wybs.lang.Builder;
import wycc.lang.Attribute;
import wycc.lang.NameID;
import wycc.lang.SyntacticElement;
import wycc.lang.SyntaxError;
import wycc.lang.Transform;
import wycc.util.ResolveError;
import wyfs.lang.Path;
import wyil.*;
import wyil.lang.*;
import wyil.util.ErrorMessages;
import static wycc.lang.SyntaxError.*;
import static wyil.util.ErrorMessages.*;
import wyautl.util.BigRational;

/**
 * The purpose of this transform is two-fold:
 * <ol>
 * <li>To inline preconditions for method invocations.</li>
 * <li>To inline preconditions for division and list/dictionary access
 * expressions</li>
 * <li>To inline postcondition checks. This involves generating the appropriate
 * shadows for local variables referenced in post-conditions</li>
 * <li>To inline dispatch choices into call-sites. This offers a useful
 * optimisation in situations when we can statically determine that a subset of
 * cases is the dispatch target.</li>
 * </ol>
 * 
 * @author David J. Pearce
 * 
 */
public class RuntimeAssertions implements Transform<WyilFile> {
	private final Builder builder;	
	private String filename;
	
	/**
	 * Determines whether verification is enabled or not.
	 */
	private boolean enabled = getEnable();
	
	public RuntimeAssertions(Builder builder) {
		this.builder = builder;
	}
	
	public RuntimeAssertions(Builder builder, String filename) {
		this.builder = builder;
		this.filename = filename;
	}
	
	public static String describeEnable() {
		return "Enable/disable runtime assertions";
	}
	
	public static boolean getEnable() {
		return true; // default value
	}
	
	public void setEnable(boolean flag) {
		this.enabled = flag;
	}
	
	public void apply(WyilFile module) {
		if (enabled) {
			this.filename = module.filename();

			for (WyilFile.Block d : module.blocks()) {
				if (d instanceof WyilFile.TypeDeclaration) {
					WyilFile.TypeDeclaration td = (WyilFile.TypeDeclaration) d;
					module.replace(td, transform(td));
				} else if (d instanceof WyilFile.FunctionOrMethodDeclaration) {
					WyilFile.FunctionOrMethodDeclaration md = (WyilFile.FunctionOrMethodDeclaration) d;
					if (!md.hasModifier(Modifier.NATIVE)) {
						// native functions/methods don't have bodies
						module.replace(md, transform(md));
					}
				}
			}
		}
	}
	
	public WyilFile.TypeDeclaration transform(WyilFile.TypeDeclaration type) {
		List<CodeBlock> constraint = type.invariant();

		if (constraint.size() > 0) {
			CodeBlock block = constraint.get(0);
			int freeSlot = block.numSlots();
			CodeBlock nBlock = new CodeBlock(1);
			for (int i = 0; i != block.size(); ++i) {
				CodeBlock.Entry entry = block.get(i);
				CodeBlock nblk = transform(entry, freeSlot, null, null);
				if (nblk != null) {
					nBlock.append(nblk);
				}
				nBlock.append(entry);
			}
			constraint = new ArrayList<CodeBlock>();
			constraint.add(nBlock);
		}

		return new WyilFile.TypeDeclaration(type.modifiers(), type.name(),
				type.type(), constraint, type.attributes());
	}
	
	public WyilFile.FunctionOrMethodDeclaration transform(WyilFile.FunctionOrMethodDeclaration method) {
		ArrayList<WyilFile.Case> cases = new ArrayList<WyilFile.Case>();
		for(WyilFile.Case c : method.cases()) {
			cases.add(transform(c,method));
		}
		return new WyilFile.FunctionOrMethodDeclaration(method.modifiers(), method.name(), method.type(), cases);
	}
	
	public WyilFile.Case transform(WyilFile.Case mcase,
			WyilFile.FunctionOrMethodDeclaration method) {
		List<CodeBlock> body = mcase.body();
		List<CodeBlock> nbody = new ArrayList<CodeBlock>();
		
		for(CodeBlock block : body) {
			CodeBlock nBlock = new CodeBlock(block.numInputs());
			int freeSlot = buildShadows(nBlock, mcase, method);

			for (int i = 0; i != block.size(); ++i) {
				CodeBlock.Entry entry = block.get(i);
				CodeBlock nblk = transform(entry, freeSlot, mcase, method);
				if (nblk != null) {
					nBlock.append(nblk);
				}
				nBlock.append(entry);
			}
			
			nbody.add(nBlock);
		}
		
		return new WyilFile.Case(nbody, mcase.precondition(),
				mcase.postcondition(), mcase.attributes());
	}
	
	/**
	 * <p>
	 * The build shadows method is used to create "shadow" copies of a
	 * function/method's parameters on entry to the method. This is necessary
	 * when a postcondition exists, as the postcondition may refer to the
	 * parameter values. In such case, however, the semantics of the language
	 * dictate that the postcondition refers to the parameter values <i>as they
	 * were on entry to the method</i>.
	 * </p>
	 * 
	 * <p>
	 * Thus, we must copy the parameter values into their shadows in the case
	 * that they are modified later on. This is potentially inefficient if none,
	 * or only some of the parameters are mentioned in the postcondition.
	 * However, a later pass could optimise this away as the copying assignmens
	 * would be dead-code.
	 * </p>
	 * 
	 * @param body
	 * @param mcase
	 * @param method
	 * @return
	 */
	public int buildShadows(CodeBlock body, WyilFile.Case mcase,
			WyilFile.FunctionOrMethodDeclaration method) {
		int freeSlot = mcase.body().get(0).numSlots();
		if (mcase.postcondition() != null) {
			//
			List<Type> params = method.type().params();
			for (int i = 0; i != params.size(); ++i) {
				Type t = params.get(i);
				body.append(Code.Assign(t, i + freeSlot, i));
			}
			freeSlot += params.size();
		}
		return freeSlot;
	}
	
	public CodeBlock transform(CodeBlock.Entry entry, int freeSlot,
			WyilFile.Case methodCase, WyilFile.FunctionOrMethodDeclaration method) {
		Code code = entry.code;
		
		try {
			// TODO: add support for indirect invokes and sends
			if(code instanceof Code.Invoke) {
				return transform((Code.Invoke)code, freeSlot, entry);
			} else if(code instanceof Code.IndexOf) {
				return transform((Code.IndexOf)code,freeSlot,entry);
			} else if(code instanceof Code.Update) {

			} else if(code instanceof Code.BinArithOp) {
				return transform((Code.BinArithOp)code,freeSlot,entry);
			} else if(code instanceof Code.Return) {
				return transform((Code.Return)code,freeSlot,entry,methodCase,method);
			}
		} catch(SyntaxError e) {
			throw e;
		} catch(ResolveError e) {
			syntaxError(e.getMessage(),filename,entry,e);
		} catch(Throwable e) {
			internalFailure(e.getMessage(),filename,entry,e);
		}
		
		return null;
	}

	/**
	 * For the invoke bytecode, we need to inline any preconditions associated
	 * with the target.
	 * 
	 * @param code
	 * @param elem
	 * @return
	 */
	public CodeBlock transform(Code.Invoke code, int freeSlot, SyntacticElement elem)
			throws Exception {
		CodeBlock precondition = findPrecondition(code.name, code.type, elem);
		if (precondition != null) {
			CodeBlock blk = new CodeBlock(0);
			List<Type> paramTypes = code.type.params();

			// TODO: mark as check block

			int[] code_operands = code.operands;
			HashMap<Integer, Integer> binding = new HashMap<Integer, Integer>();
			for (int i = 0; i != code_operands.length; ++i) {
				binding.put(i, code_operands[i]);
			}

			precondition = CodeBlock.resource(precondition,
					elem.attribute(Attribute.Source.class));

			blk.importExternal(precondition, binding);

			return blk;
		}

		return null;
	}		
	
	/**
	 * For the return bytecode, we need to inline any postcondition associated
	 * with this function/method.
	 * 
	 * @param code
	 * @param elem
	 * @return
	 */
	public CodeBlock transform(Code.Return code, int freeSlot,
			SyntacticElement elem, WyilFile.Case methodCase,
			WyilFile.FunctionOrMethodDeclaration method) {

		if (code.type != Type.T_VOID) {
			List<CodeBlock> postcondition = methodCase.postcondition();
			if (postcondition.size() > 0) {
				CodeBlock nBlock = new CodeBlock(0);
				HashMap<Integer, Integer> binding = new HashMap<Integer, Integer>();
				binding.put(0, code.operand);
				Type.FunctionOrMethod mtype = method.type();
				int pIndex = 1;
				int shadowIndex = methodCase.body().get(0).numSlots();
				for (Type p : mtype.params()) {
					binding.put(pIndex++, shadowIndex++);
				}
				CodeBlock block = CodeBlock.resource(postcondition.get(0),
						elem.attribute(Attribute.Source.class));
				nBlock.importExternal(block, binding);
				return nBlock;
			}
		}

		return null;
	}

	/**
	 * For the listload bytecode, we need to add a check that the index is
	 * within the bounds of the list. 
	 * 
	 * @param code
	 * @param elem
	 * @return
	 */
	public CodeBlock transform(Code.IndexOf code, int freeSlot,
			SyntacticElement elem) {
		
		if (code.type instanceof Type.EffectiveList || code.type instanceof Type.Strung) {
			CodeBlock blk = new CodeBlock(0);
			blk.append(Code.Const(freeSlot, Constant.V_INTEGER(BigInteger.ZERO)),
					attributes(elem));
			blk.append(Code.Assert(Type.T_INT, code.rightOperand, freeSlot,
					Code.Comparator.GTEQ, "index out of bounds (negative)"),
					attributes(elem));
			blk.append(
					Code.LengthOf(code.type, freeSlot + 1, code.leftOperand),
					attributes(elem));
			blk.append(Code.Assert(Type.T_INT, code.rightOperand, freeSlot + 1,
					Code.Comparator.LT, "index out of bounds (not less than length)"),
					attributes(elem));
			return blk;
		} else {
			return null; // FIXME
		}
	}

	/**
	 * For the update bytecode, we need to add a check the indices of any lists 
	 * used in the update are within bounds.
	 * 
	 * @param code
	 * @param elem
	 * @return
	 */
	public CodeBlock transform(Code.Update code, SyntacticElement elem) {
		return null;
	}

	/**
	 * For the case of a division operation, we need to check that the divisor
	 * is not zero.
	 * 
	 * @param code
	 * @param elem
	 * @return
	 */
	public CodeBlock transform(Code.BinArithOp code, int freeSlot, SyntacticElement elem) {
		
		if(code.kind == Code.BinArithKind.DIV) {
			CodeBlock blk = new CodeBlock(0);
			if (code.type instanceof Type.Int) {
				blk.append(Code.Const(freeSlot,Constant.V_INTEGER(BigInteger.ZERO)),
						attributes(elem));
			} else {
				blk.append(Code.Const(freeSlot,Constant.V_DECIMAL(BigDecimal.ZERO)),
						attributes(elem));
			}
			blk.append(Code.Assert(code.type, code.rightOperand, freeSlot,
					Code.Comparator.NEQ, "division by zero"), attributes(elem));
			return blk;
		} 
		
		// not a division bytecode, so ignore
		return null;					
	}
	
	protected CodeBlock findPrecondition(NameID name, Type.FunctionOrMethod fun,
			SyntacticElement elem) throws Exception {		
		Path.Entry<WyilFile> e = builder.project().get(name.module(),WyilFile.ContentType);
		if(e == null) {
			syntaxError(
					errorMessage(ErrorMessages.RESOLUTION_ERROR, name.module()
							.toString()), filename, elem);
		}
		WyilFile m = e.read();
		WyilFile.FunctionOrMethodDeclaration method = m.functionOrMethod(name.name(),fun);
	
		for(WyilFile.Case c : method.cases()) {
			// FIXME: this is a hack for now, since method cases don't do
			// anything.
			if(c.precondition().size() > 0) {
				return c.precondition().get(0);
			} 
		}
		return null;
	}
	
	private java.util.List<Attribute> attributes(SyntacticElement elem) {
		return elem.attributes();
	}
}
