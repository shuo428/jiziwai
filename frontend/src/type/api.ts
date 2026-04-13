export interface Result<T = unknown> {
    code: number;
    message: string;
    data: T;
}

export interface PageResult<T> {
    records: T[];
    total: number;
    current: number;
    size: number;
}