import println from whiley.lang.System

type nat is int where $ >= 0

function g([nat] xs) => [nat]:
    return xs

function f([nat] xs) => [nat]:
    return g(xs)

method main(System.Console sys) => void:
    rs = f([1, 2, 3])
    sys.out.println(Any.toString(rs))