// This file is part of the Whiley Intermediate Language (wyil).
//
// The Whiley Intermediate Language is free software; you can redistribute 
// it and/or modify it under the terms of the GNU General Public 
// License as published by the Free Software Foundation; either 
// version 3 of the License, or (at your option) any later version.
//
// The Whiley Intermediate Language is distributed in the hope that it 
// will be useful, but WITHOUT ANY WARRANTY; without even the 
// implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR 
// PURPOSE.  See the GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public 
// License along with the Whiley Intermediate Language. If not, see 
// <http://www.gnu.org/licenses/>
//
// Copyright 2010, David James Pearce. 

package wyil.lang;

import java.util.*;
import wyil.lang.RVal.LVal;
import wyjvm.lang.Bytecode;

public abstract class Code {

	// ==========================================
	// =============== Methods ==================
	// ==========================================
	
	/**
	 * Determine which variables are used by this code.
	 */
	public static void usedVariables(Code c, Set<String> uses) {
		if(c instanceof Assign) {
			Assign a = (Assign) c;			
			RVal.usedVariables(a.lhs,uses);
			RVal.usedVariables(a.rhs,uses);
		} 
	}

	/**
	 * Substitute all occurrences of variable from with variable to.
	 * 
	 * @param c
	 * @param uses
	 */
	public static Code substitute(HashMap<String,RVal> binding, Code c) {
		if(c instanceof Assign) {
			 Assign a = (Assign) c;
			return new Assign((LVal) RVal.substitute(binding, a.lhs), RVal
					.substitute(binding, a.rhs));
		} else if(c instanceof UnOp) {
			UnOp u = (UnOp) c;
			return new UnOp(u.op, (LVal) RVal.substitute(binding, u.lhs), RVal
					.substitute(binding, u.rhs));
		} else if(c instanceof BinOp) {
			BinOp u = (BinOp) c;
			return new BinOp(u.op, (LVal) RVal.substitute(binding, u.lhs), RVal
					.substitute(binding, u.rhs1), RVal.substitute(binding,
					u.rhs2));
		} else if(c instanceof NaryOp) {
			NaryOp u = (NaryOp) c;
			ArrayList<RVal> args = new ArrayList<RVal>();
			for (RVal r : u.args) {
				args.add(RVal.substitute(binding, r));
			}
			return new NaryOp(u.op, (LVal) RVal.substitute(binding, u.lhs), args);
		} else if(c instanceof IfGoto) {
			IfGoto u = (IfGoto) c;
			return new IfGoto(u.type, u.op, RVal.substitute(binding, u.lhs),
					RVal.substitute(binding, u.rhs), u.target);
		} else {
			return c;
		}
	}	
	
	/**
	 * This represents a simple assignment between two variables.
	 * 
	 * @author djp
	 * 
	 */
	public final static class Assign extends Code {		
		public final LVal lhs;
		public final RVal rhs;
		
		public Assign(LVal lhs, RVal rhs) {			
			this.lhs = lhs;
			this.rhs = rhs;
		}
		
		public boolean equals(Object o) {
			if(o instanceof Assign) {
				Assign a = (Assign) o;
				return lhs.equals(a.lhs) && rhs.equals(a.rhs);
				
			}
			return false;
		}
		
		public int hashCode() {
			return lhs.hashCode() + rhs.hashCode();			
		}
		
		public String toString() {
			return lhs + " := " + rhs;
		}		
	}

	public final static class Return extends Code {
		public final Type type;
		public final RVal rhs;
		
		public Return(Type type,RVal rhs) {
			this.type = type;			
			this.rhs = rhs;
		}
		
		public boolean equals(Object o) {
			if(o instanceof Return) {
				Return a = (Return) o;
				return type.equals(a.type) && rhs.equals(a.rhs);
				
			}
			return false;
		}
		
		public int hashCode() {
			return type.hashCode() + rhs.hashCode();			
		}
		
		public String toString() {
			return "return[" + type + "] " + rhs;
		}		
	}
	
	/**
	 * This represents a simple assignment between two variables.
	 * 
	 * @author djp
	 * 
	 */
	public final static class BinOp extends Code {
		public final BOP op;		
		public final LVal lhs;
		public final RVal rhs1;
		public final RVal rhs2;
		
		public BinOp(BOP op, LVal lhs, RVal rhs1, RVal rhs2) {
			this.op = op;			
			this.lhs = lhs;
			this.rhs1 = rhs1;
			this.rhs2 = rhs2;
		}
		
		public boolean equals(Object o) {
			if(o instanceof BinOp) {
				BinOp a = (BinOp) o;
				return op == a.op && lhs.equals(a.lhs)
						&& rhs1.equals(a.rhs1) && rhs2.equals(a.rhs2);
				
			}
			return false;
		}
		
		public int hashCode() {
			return op.hashCode() + lhs.hashCode()
					+ rhs1.hashCode() + rhs2.hashCode();
		}
		
		public String toString() {
			return lhs + " := " + rhs1 + " " + op + " " + rhs2;
		}		
	}
	
	public final static class UnOp extends Code {
		public final UOP op;		
		public final LVal lhs;
		public final RVal rhs;		
		
		public UnOp(UOP op, LVal lhs, RVal rhs) {
			this.op = op;			
			this.lhs = lhs;
			this.rhs = rhs;
		}
		
		public boolean equals(Object o) {
			if(o instanceof UnOp) {
				UnOp a = (UnOp) o;
				return op == a.op && lhs.equals(a.lhs)
						&& rhs.equals(a.rhs);
				
			}
			return false;
		}
		
		public int hashCode() {
			return op.hashCode() + lhs.hashCode()
					+ rhs.hashCode();
		}
		
		public String toString() {
			if(op == UOP.LENGTHOF){
				return lhs + " := |" + rhs + "|";
			} else {
				return lhs + " := " + op + rhs;
			}
		}		
	}
	
	public final static class NaryOp extends Code {
		public final NOP op;		
		public final LVal lhs;
		public final List<RVal> args;		
		
		public NaryOp(NOP op, LVal lhs, RVal... args) {
			this.op = op;			
			this.lhs = lhs;
			ArrayList<RVal> tmp = new ArrayList<RVal>();
			for(RVal r : args) {
				tmp.add(r);
			}
			this.args = Collections.unmodifiableList(tmp); 
		}
		
		public NaryOp(NOP op, LVal lhs, Collection<RVal> args) {
			this.op = op;			
			this.lhs = lhs;
			this.args = Collections.unmodifiableList(new ArrayList<RVal>(args));			
		}
		
		public boolean equals(Object o) {
			if(o instanceof NaryOp) {
				NaryOp a = (NaryOp) o;
				return op == a.op && lhs.equals(a.lhs)
						&& args.equals(a.args);
				
			}
			return false;
		}
		
		public int hashCode() {
			return op.hashCode() + lhs.hashCode()
					+ args.hashCode();
		}
		
		public String toString() {
			String rhs = "";
			switch (op) {
			case SETGEN: {
				rhs += "{";
				boolean firstTime = true;
				for (RVal r : args) {
					if (!firstTime) {
						rhs += ",";
					}
					firstTime = false;
					rhs += r;
				}
				rhs += "}";
				break;
			}
			case LISTGEN: {
				rhs += "[";
				boolean firstTime = true;
				for (RVal r : args) {
					if (!firstTime) {
						rhs += ",";
					}
					firstTime = false;
					rhs += r;
				}
				rhs += "]";
				break;
			}
			case SUBLIST:
				rhs += args.get(0) + "[" + args.get(1) + ":" + args.get(2)
						+ "]";
				break;
			}
			return lhs + " := " + rhs;
		}
	}
	
	public final static class Invoke extends Code {
		public final Type.Fun type;
		public final NameID name;
		public final LVal lhs;
		public final List<RVal> args;
		public final int caseNum;

		public Invoke(Type.Fun type, NameID name, int caseNum, LVal lhs, RVal... args) {
			this.type = type;
			this.name = name;
			this.caseNum = caseNum;
			this.lhs = lhs;
			ArrayList<RVal> tmp = new ArrayList<RVal>();
			for(RVal r : args) {
				tmp.add(r);
			}
			this.args = Collections.unmodifiableList(tmp); 
		}

		public Invoke(Type.Fun type, NameID name, int caseNum, LVal lhs,
				Collection<RVal> args) {
			this.type = type;
			this.name = name;
			this.caseNum = caseNum;
			this.lhs = lhs;
			this.args = Collections.unmodifiableList(new ArrayList<RVal>(args));
		}

		public boolean equals(Object o) {
			if (o instanceof Invoke) {
				Invoke a = (Invoke) o;
				if (lhs == null) {
					return type.equals(a.type) && name.equals(a.name)
							&& caseNum == a.caseNum && a.lhs == null
							&& args.equals(a.args);
				} else {
					return type.equals(a.type) && name.equals(a.name)
							&& caseNum == a.caseNum && lhs.equals(a.lhs)
							&& args.equals(a.args);
				}
			}
			return false;
		}

		public int hashCode() {
			if (lhs == null) {
				return name.hashCode() + caseNum + type.hashCode() + args.hashCode();
			} else {
				return name.hashCode() + caseNum + type.hashCode() + lhs.hashCode()
						+ args.hashCode();
			}
		}

		public String toString() {
			String rhs = "";
			boolean firstTime = true;
			for (RVal v : args) {
				if (!firstTime) {
					rhs += ",";
				}
				firstTime = false;
				rhs += v;
			}
			String n = name + ":" + type;
			if(caseNum > 0) {
				n += ":" + caseNum;
			}
			if(lhs == null) {
				return n + ":(" + rhs + ")";
			} else {
				return lhs + " := " + n + ":(" + rhs + ")";
			}
		}
	}
	
	public final static class Debug extends Code {				
		public final RVal rhs;
		
		public Debug(RVal rhs) {			
			this.rhs = rhs;
		}
		
		public boolean equals(Object o) {
			if(o instanceof Debug) {
				Debug a = (Debug) o;
				return rhs.equals(a.rhs);
				
			}
			return false;
		}
		
		public int hashCode() {
			return rhs.hashCode();			
		}
		
		public String toString() {
			return "debug " + rhs;
		}		
	}
	
	public final static class Fail extends Code {				
		public final String msg;
		
		public Fail(String msg) {			
			this.msg = msg;
		}
		
		public boolean equals(Object o) {
			if(o instanceof Fail) {
				Fail a = (Fail) o;
				return msg.equals(a.msg);
				
			}
			return false;
		}
		
		public int hashCode() {
			return msg.hashCode();			
		}
		
		public String toString() {
			return "fail \"" + msg + "\"";
		}		
	}
	
	/**
	 * This represents a conditional branching instruction
	 * @author djp
	 *
	 */
	public final static class IfGoto extends Code {
		public final COP op;
		public final Type type;
		public final RVal lhs;
		public final RVal rhs;
		public final String target;

		public IfGoto(Type type, COP op, RVal lhs, RVal rhs, String target) {
			this.op = op;
			this.type = type;
			this.lhs = lhs;
			this.rhs = rhs;
			this.target = target;
		}
		
		public int hashCode() {
			return op.hashCode() + type.hashCode() + lhs.hashCode()
					+ rhs.hashCode() + target.hashCode();
		}
		
		public boolean equals(Object o) {
			if(o instanceof IfGoto) {
				IfGoto ig = (IfGoto) o;
				return op == ig.op && type.equals(ig.type)
						&& lhs.equals(ig.lhs) && rhs.equals(ig.rhs)
						&& target.equals(ig.target);
			}
			return false;
		}
	
		public String toString() {
			return "if " + lhs + " " + op + " " + rhs + " goto " + target;
		}
	}	
	
	/**
	 * This represents an unconditional branching instruction
	 * @author djp
	 *
	 */
	public final static class Goto extends Code  {
		public final String target;
		
		public Goto(String target) {
			this.target = target;
		}
		
		public int hashCode() {
			return target.hashCode();
		}
		
		public boolean equals(Object o) {
			if(o instanceof Goto) {
				return target.equals(((Goto)o).target);
			}
			return false;
		}
		
		public String toString() {
			return "goto " + target;
		}
	}	
	
	/**
	 * This represents the target of a branching instruction
	 * @author djp
	 *
	 */
	public final static class Label extends Code  {
		public final String label;
		
		public Label(String label) {
			this.label = label;
		}
		
		public int hashCode() {
			return label.hashCode();
		}
		
		public boolean equals(Object o) {
			if(o instanceof Label) {
				return label.equals(((Label)o).label);
			}
			return false;
		}
		
		public String toString() {
			return "." + label;
		}
	}	
	
	public static class Skip extends Code  {
		public int hashCode() {
			return 1;
		}
		
		public boolean equals(Object o) {
			return o instanceof Skip;
		}
		
		public String toString() {
			return "skip";
		}
	}
	public static class ExternJvm extends Skip  {
		public final List<Bytecode> bytecodes;

		public ExternJvm(Collection<Bytecode> bytecodes) {
			this.bytecodes = Collections
					.unmodifiableList(new ArrayList<Bytecode>(bytecodes));
		}
		
		public int hashCode() {
			return 1;
		}
		
		public boolean equals(Object o) {
			return o instanceof Skip;
		}
		
		public String toString() {
			return "skip";
		}
	}
	
	public enum UOP { 
		NEG() {
			public String toString() { return "-"; }
		},
		NOT() {
			public String toString() { return "!"; }
		},
		LENGTHOF() {
			public String toString() { return "||"; }
		}
	}
	public enum BOP { 
		ADD{
			public String toString() { return "+"; }
		},
		SUB{
			public String toString() { return "-"; }
		},
		MUL{
			public String toString() { return "*"; }
		},
		DIV{
			public String toString() { return "/"; }
		},
		UNION{
			public String toString() { return "+"; }
		},
		INTERSECT{
			public String toString() { return "&"; }
		},
		DIFFERENCE{
			public String toString() { return "-"; }
		}
	};	
	public enum COP { 
		EQ() {
			public String toString() { return "=="; }
		},
		NEQ{
			public String toString() { return "!="; }
		},
		LT{
			public String toString() { return "<"; }
		},
		LTEQ{
			public String toString() { return "<="; }
		},
		GT{
			public String toString() { return ">"; }
		},
		GTEQ{
			public String toString() { return ">="; }
		},
		ELEMOF{
			public String toString() { return "in"; }
		},
		SUBSET{
			public String toString() { return "<"; }
		},
		SUBSETEQ{
			public String toString() { return "<="; }
		}
	};	
	public enum NOP { 
		SETGEN,
		LISTGEN,
		SUBLIST
	}
}
