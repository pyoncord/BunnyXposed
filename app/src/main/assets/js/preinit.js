let originalObjectCreate = Object.create

Object.create = function create(proto) {
    let ret = originalObjectCreate.apply(this, arguments)
    if (proto === null) {
        Object.create = originalObjectCreate
        return modules = ret
    }

    return ret
}

Object.freeze = Object
