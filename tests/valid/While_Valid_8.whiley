import whiley.lang.System

function extract([int] ls) => [int]:
    int i = 0
    [int] r = [1]
    while i < |ls|:
        r = r ++ [ls[i]]
        i = i + 1
    return r

method main(System.Console sys) => void:
    [int] rs = extract([-2, -3, 1, 2, -23, 3, 2345, 4, 5])
    sys.out.println(Any.toString(rs))
