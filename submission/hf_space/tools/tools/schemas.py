from pydantic import BaseModel, Field


class Flag(BaseModel):
    severity: int = Field(ge=1, le=5)
    description: str
    citation: str


class Citation(BaseModel):
    source: str
    text: str


class AegisResponse(BaseModel):
    flags: list[Flag] = Field(default_factory=list)
    citations: list[Citation] = Field(default_factory=list)
    confidence: float = Field(ge=0.0, le=1.0)
    defer_to_professional: bool = False
    explanation: str = ""


class DrugInfo(BaseModel):
    generic_name: str
    rxcui: str
    category: str  # OTC, Rx, Controlled, Supplement


class ProductDecomposition(BaseModel):
    product: str
    ingredients: list[DrugInfo]
    citation: str


class TermDefinition(BaseModel):
    term: str
    plain_language_definition: str
    citation: str


class GuidelineRecommendation(BaseModel):
    title: str
    grade: str  # A or B
    description: str
    population: str
    citation: str
