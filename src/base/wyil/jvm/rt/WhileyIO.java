package wyil.jvm.rt;

import java.math.*;
import java.util.*;
import java.io.*;
import java.lang.reflect.*;

public class WhileyIO {
	public static WhileyProcess openFile(ArrayList name) {
		WhileyRecord r = new WhileyRecord();
		try {
			String filename = Util.toString(name);
			FileInputStream fin = new FileInputStream(filename);
			r.put("fileName", name);
			r.put("$fin", fin);
			WhileyProcess p = new WhileyProcess(r);
			return p;
		} catch(FileNotFoundException e) {
			r.put("msg", e.getMessage());			
		}
		return null;
	}
	
	public static void closeFile(WhileyProcess p) {
		FileInputStream fin = (FileInputStream) ((HashMap) p.state())
				.get("$fin");
		try {
			fin.close();
		} catch (IOException ioe) {
			// what to do here??
		}
	}
	
	public static ArrayList readFile(WhileyProcess p, BigRational max) {		
		FileInputStream fin = (FileInputStream) ((HashMap) p.state())
				.get("$fin");
		
		ArrayList r = new ArrayList();
		byte[] bytes = new byte[max.intValue()];		
		try {
			int nbytes = fin.read(bytes);
			for(int i=0;i!=nbytes;++i) {				
				r.add(BigRational.valueOf(bytes[i] & 0xFF));
			}
			System.out.println("READ: " + nbytes);
		} catch (IOException ioe) {
			// what to do here??
		}
		
		return r;		
	}
	
	private static final int CHUNK_SIZE = 1024;
	public static ArrayList readFile(WhileyProcess p) {		
		FileInputStream fin = (FileInputStream) ((HashMap) p.state())
				.get("$fin");
		
		ArrayList r = new ArrayList();				
		try {
			int nbytes = 0;
			do {
				byte[] bytes = new byte[CHUNK_SIZE];
				nbytes = fin.read(bytes);
				for(int i=0;i!=nbytes;++i) {
					r.add(BigRational.valueOf(bytes[i] & 0xFF));
				}
			} while(nbytes == CHUNK_SIZE);			
		} catch (IOException ioe) {
			// what to do here??
		}
		
		return r;		
	}
	
	public static Method functionRef(String clazz, String name) {
		try {
			Class cl = Class.forName(clazz);
			for(Method m : cl.getDeclaredMethods()) {
				if(m.getName().equals(name)) {
					return m;
				}
			}
			throw new RuntimeException("Method Not Found: " + clazz + ":" + name);
		} catch(ClassNotFoundException e) {
			throw new RuntimeException("Class Not Found: " + clazz);
		}
	}
}
