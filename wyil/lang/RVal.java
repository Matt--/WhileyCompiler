package wyil.lang;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Set;

public abstract class RVal {
	public abstract Type type();
	
	public static abstract class LVal extends RVal {}
	
	public static void usedVariables(RVal r, Set<String> uses) {
		if (r instanceof Variable) {
			Variable v = (Variable) r;
			uses.add(v.name);
		}
	}
	
	/**
	 * Substitute all occurrences of variable from with variable to.
	 * 
	 * @param c
	 * @param uses
	 */
	public static RVal substitute(HashMap<String,RVal> binding, RVal r) {
		if (r instanceof Variable) {
			Variable v = (Variable) r;
			RVal rv = binding.get(v.name);
			if (rv != null) {
				return rv;
			} 
		}
		return r;
	}
	
	public static Variable VAR(Type t, String v) {
		return get(new Variable(t,v));
	}
	
	public static class Variable extends LVal {
		public final String name;
		public final Type type;
		
		Variable(Type type, String name) {
			this.name = name;
			this.type = type;
		}
		public Type type() {
			return type;
		}
		public int hashCode() {
			return type.hashCode() + name.hashCode();
		}
		public boolean equals(Object o) {
			if(o instanceof Variable) {
				Variable v = (Variable) o;
				return type.equals(v.type) && name.equals(v.name);
			}
			return false;
		}
		public String toString() {
			return "(" + type + ") " + name;
		}
	}
	
	private static final ArrayList<RVal> values = new ArrayList<RVal>();
	private static final HashMap<RVal,Integer> cache = new HashMap<RVal,Integer>();
	
	private static <T extends RVal> T get(T type) {
		Integer idx = cache.get(type);
		if(idx != null) {
			return (T) values.get(idx);
		} else {					
			cache.put(type, values.size());
			values.add(type);
			return type;
		}
	}
}
