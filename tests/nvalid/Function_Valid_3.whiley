import println from whiley.lang.System

type fr3nat is int

function f(int x) => string:
    return Any.toString(x)

method main(System.Console sys) => void:
    y = 234987234987234982304980130982398723
    sys.out.println(f(y))